package dev.nectariich.hypergraphical.scene;

import dev.nectariich.hypergraphical.HgConfig;
import dev.nectariich.hypergraphical.api.ModelInstance;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Client-side scene graph: every {@link ModelInstance} that exists, and the per-frame job of
 * reducing that to the subset worth drawing.
 *
 * <p><b>On the lack of a spatial index.</b> Culling is a flat scan with a squared-distance
 * reject in front of the frustum test. That sounds lazy for a renderer that advertises being
 * optimised, but the reject is a handful of arithmetic ops with no pointer chasing, so ~40k
 * instances cost well under a millisecond, and a uniform grid would need instances to notify
 * the scene on every move to stay correct. Instances are mutated directly by consumer code,
 * so that notification is exactly the thing we cannot cheaply guarantee. Draw submission,
 * not culling, is the bottleneck at this scale. Past a few hundred thousand instances the
 * right answer is not a grid either, it is GPU culling with an indirect draw buffer.
 */
public final class HgScene {

    /** Ticks between automatic world-light resamples for a stationary instance. */
    private static final int LIGHT_REFRESH_TICKS = 10;

    /** Upper bound on light resamples per frame, so a mass spawn cannot spike the frame. */
    private static final int LIGHT_SAMPLE_BUDGET = 512;

    private final ObjectArrayList<ModelInstance> instances = new ObjectArrayList<>();

    private int lastVisibleCount;
    private int lastCulledCount;
    private int tick;

    // ------------------------------------------------------------------ mutation
    public ModelInstance add(ModelInstance instance) {
        this.instances.add(instance);
        return instance;
    }

    public void remove(ModelInstance instance) {
        instance.markRemoved();
    }

    public void clear() {
        for (ModelInstance instance : this.instances) {
            instance.markRemoved();
        }
        this.instances.clear();
    }

    public int size() {
        return this.instances.size();
    }

    public ObjectArrayList<ModelInstance> all() {
        return this.instances;
    }

    @Nullable
    public ModelInstance byId(int id) {
        for (ModelInstance instance : this.instances) {
            if (instance.id() == id) {
                return instance;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------- tick
    /** Sweeps removed instances. Cheap: only walks the list when something was removed. */
    public void tick() {
        this.tick++;
        boolean needsSweep = false;
        for (ModelInstance instance : this.instances) {
            if (instance.removed()) {
                needsSweep = true;
                break;
            }
        }
        if (needsSweep) {
            this.instances.removeIf(ModelInstance::removed);
        }
    }

    public int currentTick() {
        return this.tick;
    }

    // -------------------------------------------------------------------- culling
    /**
     * Fills {@code out} with instances that survive visibility, distance and frustum culling,
     * refreshing world light for the ones that need it.
     *
     * @param frustum vanilla camera frustum, or {@code null} to skip frustum culling (probe
     *                capture passes its own, and an omnidirectional bake passes none)
     */
    public void gatherVisible(Vec3 cameraPos,
                              @Nullable net.minecraft.client.renderer.culling.Frustum frustum,
                              boolean forProbeCapture,
                              ObjectArrayList<ModelInstance> out) {
        out.clear();

        final double maxDist = HgConfig.MAX_RENDER_DISTANCE.get();
        final double maxDistSq = maxDist * maxDist;
        final int cap = HgConfig.MAX_INSTANCES_PER_FRAME.get();
        final boolean doFrustum = frustum != null && HgConfig.FRUSTUM_CULLING.get();
        final Level level = Minecraft.getInstance().level;

        int culled = 0;
        int lightBudget = LIGHT_SAMPLE_BUDGET;

        for (int i = 0, n = this.instances.size(); i < n; i++) {
            ModelInstance instance = this.instances.get(i);

            if (!instance.visible() || (forProbeCapture && !instance.visibleInProbes())) {
                culled++;
                continue;
            }

            double dx = instance.x() - cameraPos.x;
            double dy = instance.y() - cameraPos.y;
            double dz = instance.z() - cameraPos.z;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) {
                culled++;
                continue;
            }

            // Bounds are unknown until the mesh is resident. Draw anyway rather than pop the
            // model in a frame late; the mesh manager fills the radius in on first upload.
            if (doFrustum && instance.hasBounds() && !frustum.isVisible(instance.worldBounds())) {
                culled++;
                continue;
            }

            if (level != null && instance.rawPackedLight() < 0 && lightBudget > 0
                    && needsLightResample(instance)) {
                instance.cacheLight(sampleLight(level, instance), this.tick);
                lightBudget--;
            }

            out.add(instance);
            if (out.size() >= cap) {
                culled += n - i - 1;
                break;
            }
        }

        this.lastVisibleCount = out.size();
        this.lastCulledCount = culled;
    }

    /**
     * Whether an instance's cached world light is stale.
     *
     * <p>The never-sampled case is tested explicitly instead of being folded into the
     * staleness subtraction, and that is not a style preference: {@code tick - MIN_VALUE}
     * overflows to a negative number, so a bare {@code >= LIGHT_REFRESH_TICKS} comparison is
     * false for every never-sampled instance and stays false forever. The symptom is not a
     * subtly wrong light level, it is every model stuck at light zero &mdash; and a metallic
     * material then renders pure black, because it has no diffuse term and both the probe
     * and sun specular terms are gated on skylight.
     */
    private boolean needsLightResample(ModelInstance instance) {
        int sampledAt = instance.lightSampleTick();
        return sampledAt == ModelInstance.LIGHT_NEVER_SAMPLED
                || this.tick - sampledAt >= LIGHT_REFRESH_TICKS;
    }

    private static int sampleLight(Level level, ModelInstance instance) {
        BlockPos pos = BlockPos.containing(instance.x(), instance.y(), instance.z());
        if (!level.isLoaded(pos)) {
            return LightTexture.pack(0, 15);
        }
        return LightTexture.pack(level.getBrightness(LightLayer.BLOCK, pos),
                level.getBrightness(LightLayer.SKY, pos));
    }

    /** Resolved packed lightmap coordinate for an instance, honouring a manual override. */
    public static int lightOf(ModelInstance instance) {
        int manual = instance.rawPackedLight();
        return manual >= 0 ? manual : instance.cachedLight();
    }

    public int lastVisibleCount() {
        return this.lastVisibleCount;
    }

    public int lastCulledCount() {
        return this.lastCulledCount;
    }
}
