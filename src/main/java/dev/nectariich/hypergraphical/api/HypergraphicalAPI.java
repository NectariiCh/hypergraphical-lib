package dev.nectariich.hypergraphical.api;

import dev.nectariich.hypergraphical.HgConfig;
import dev.nectariich.hypergraphical.HypergraphicalClient;
import dev.nectariich.hypergraphical.material.HgMaterial;
import dev.nectariich.hypergraphical.reflect.ReflectionProbe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The public entry point.
 *
 * <p>Everything here is <b>client-side only</b> and must be called from the client thread.
 * There is no server component and nothing is synchronised: a Hypergraphical model is a
 * rendering construct, like a particle, not a world object. If you want models that all
 * players see, drive them from your own packets and call this on each client.
 *
 * <h2>Typical use</h2>
 *
 * <pre>{@code
 * // Place a model at arbitrary, non-grid coordinates.
 * ModelInstance statue = HypergraphicalAPI.spawn(
 *         new ResourceLocation("mymod", "models/obj/statue.obj"),
 *         new ResourceLocation("mymod", "polished_brass"),
 *         104.5, 71.25, -338.75);
 *
 * statue.setScale(2.0F)
 *       .setEulerDegrees(45.0F, 0.0F, 0.0F)
 *       .setSmoothness(0.9F);   // override the material, no clone needed
 *
 * // Give it something real to reflect.
 * HypergraphicalAPI.addProbe(new Vec3(104.5, 72.0, -338.75), 16.0);
 * }</pre>
 *
 * <p>Instances persist until removed or until the level unloads. They cost nothing when out
 * of range: culling happens before any GPU work.
 */
public final class HypergraphicalAPI {

    private HypergraphicalAPI() {}

    // -------------------------------------------------------------------- models
    /**
     * Places a mesh in the world.
     *
     * @param mesh     an {@code .obj} resource path, with or without the extension, e.g.
     *                 {@code mymod:models/obj/lantern.obj}. Loaded and parsed off-thread on
     *                 first use; the instance simply is not drawn until it is resident.
     * @param material a material id resolving to {@code assets/&lt;ns&gt;/hg_materials/&lt;name&gt;.json},
     *                 a {@link #registerMaterial registered} material, or {@code null} to use
     *                 the materials derived from the model's own {@code .mtl}
     */
    public static ModelInstance spawn(ResourceLocation mesh, @Nullable ResourceLocation material,
                                      double x, double y, double z) {
        ModelInstance instance = new ModelInstance(mesh, material, x, y, z);
        HypergraphicalClient.scene().add(instance);
        HypergraphicalClient.meshes().preload(mesh);
        return instance;
    }

    public static ModelInstance spawn(ResourceLocation mesh, @Nullable ResourceLocation material,
                                      Vec3 position) {
        return spawn(mesh, material, position.x, position.y, position.z);
    }

    /** Removes an instance. Safe to call more than once. */
    public static void destroy(ModelInstance instance) {
        instance.markRemoved();
    }

    /** Removes every instance. */
    public static void clearAll() {
        HypergraphicalClient.scene().clear();
    }

    public static int instanceCount() {
        return HypergraphicalClient.scene().size();
    }

    /** Starts loading a mesh so it is resident before you need it. */
    public static void preloadMesh(ResourceLocation mesh) {
        HypergraphicalClient.meshes().preload(mesh);
    }

    /** Whether a mesh is on the GPU and will draw this frame. */
    public static boolean isMeshResident(ResourceLocation mesh) {
        return HypergraphicalClient.meshes().get(mesh) != null;
    }

    // ----------------------------------------------------------------- materials
    /**
     * Registers a material from code. Overrides any pack JSON with the same id and survives
     * resource reloads, so use a namespace you own.
     */
    public static void registerMaterial(HgMaterial material) {
        HypergraphicalClient.materials().register(material);
    }

    public static boolean hasMaterial(ResourceLocation id) {
        return HypergraphicalClient.materials().has(id);
    }

    /**
     * Resolves a material id to the material that would actually be used, falling back to a
     * magenta placeholder if nothing matches.
     */
    public static HgMaterial resolveMaterial(ResourceLocation id) {
        return HypergraphicalClient.materials().resolve(id, null);
    }

    // -------------------------------------------------------------------- probes
    /**
     * Adds a reflection probe with a cubic box of influence.
     *
     * @param radius half-extent of the box, in blocks. Instances inside it use this probe,
     *               and it doubles as the proxy volume for parallax correction, so it should
     *               roughly match the room or area the probe sits in.
     */
    public static ReflectionProbe addProbe(Vec3 center, double radius) {
        return HypergraphicalClient.probes().add(center, radius);
    }

    /** Adds a reflection probe with an explicit box, resolution and refresh mode. */
    public static ReflectionProbe addProbe(Vec3 center, Vec3 halfExtents, int resolution,
                                           ReflectionProbe.Mode mode) {
        return HypergraphicalClient.probes().add(center, halfExtents, resolution, mode);
    }

    public static void removeProbe(ReflectionProbe probe) {
        HypergraphicalClient.probes().remove(probe);
    }

    public static void clearProbes() {
        HypergraphicalClient.probes().clear();
    }

    public static List<ReflectionProbe> probes() {
        return HypergraphicalClient.probes().probes();
    }

    /** Forces every probe to re-capture. Call after changing the world around them. */
    public static void rebakeProbes() {
        HypergraphicalClient.probes().invalidateAll();
    }

    /**
     * The capture strategy currently in effect, which may be a downgrade from the configured
     * one (see {@code dev.nectariich.hypergraphical.reflect.ProbeCapture}).
     */
    public static HgConfig.ProbeCapture activeProbeStrategy() {
        return HypergraphicalClient.probes().effectiveStrategy();
    }

    // ----------------------------------------------------------------- diagnostics
    /** Instances submitted to the GPU on the last frame, after culling. */
    public static int lastDrawnInstances() {
        return HypergraphicalClient.renderer().lastInstanceCount();
    }

    /** Draw calls issued by the last frame's passes. */
    public static int lastDrawCalls() {
        return HypergraphicalClient.renderer().lastDrawCalls();
    }
}
