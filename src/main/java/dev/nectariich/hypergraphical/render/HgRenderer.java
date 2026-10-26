package dev.nectariich.hypergraphical.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.nectariich.hypergraphical.HgConfig;
import dev.nectariich.hypergraphical.Hypergraphical;
import dev.nectariich.hypergraphical.api.ModelInstance;
import dev.nectariich.hypergraphical.material.HgMaterial;
import dev.nectariich.hypergraphical.material.MaterialManager;
import dev.nectariich.hypergraphical.material.TextureBank;
import dev.nectariich.hypergraphical.mesh.GpuMesh;
import dev.nectariich.hypergraphical.mesh.MeshManager;
import dev.nectariich.hypergraphical.reflect.IblProcessor;
import dev.nectariich.hypergraphical.reflect.ProbeManager;
import dev.nectariich.hypergraphical.reflect.ReflectionProbe;
import dev.nectariich.hypergraphical.scene.HgScene;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The Hypergraphical render pass.
 *
 * <p>Runs twice per frame from {@code RenderLevelStageEvent}: opaque and cutout geometry
 * before translucent blocks, alpha-blended geometry after them. Both passes write into
 * Minecraft's main render target and share its depth buffer, which is what makes terrain
 * occlude our models and our models occlude terrain without either side knowing about the
 * other.
 *
 * <h2>How a frame is put together</h2>
 *
 * <ol>
 *   <li><b>Cull</b> to the visible set (distance, then frustum).</li>
 *   <li><b>Batch</b> by {@code (mesh, sub-mesh, material, probe pair)}. Everything sharing
 *       those becomes one instanced draw.</li>
 *   <li><b>Stream</b> every batch's per-instance data into one buffer, uploaded once.</li>
 *   <li><b>Draw</b>, sorted so that state changes are rare: material uniforms and texture
 *       binds only move on a batch boundary.</li>
 * </ol>
 *
 * <p>The batch key is the whole performance story. A thousand copies of one model with one
 * material is a single draw call regardless of where they are, because position, rotation,
 * scale, tint, lightmap and the per-instance metallic/smoothness overrides all travel in the
 * instance stream rather than in uniforms. Splitting only happens on things that genuinely
 * require different GPU state: different geometry, different textures, or different
 * reflection probes.
 *
 * <p>Probes are part of the key because GL 3.3 has no cubemap arrays, so a probe pair means
 * two sampler bindings and those cannot vary within a draw. Probes are spatially coherent, so
 * in practice this splits a scene by region, which is where it would split anyway.
 */
public final class HgRenderer implements AutoCloseable {

    /** Texture units. Fixed assignment so sampler uniforms are set once at compile time. */
    private static final int UNIT_ALBEDO = 0;
    private static final int UNIT_EMISSIVE = 1;
    private static final int UNIT_METALLIC = 2;
    private static final int UNIT_ROUGHNESS = 3;
    private static final int UNIT_LIGHTMAP = 4;
    private static final int UNIT_BRDF = 5;
    private static final int UNIT_PROBE_A_RADIANCE = 6;
    private static final int UNIT_PROBE_B_RADIANCE = 7;
    private static final int UNIT_PROBE_A_IRRADIANCE = 8;
    private static final int UNIT_PROBE_B_IRRADIANCE = 9;

    /** Which of the two passes is being drawn. */
    public enum Pass {
        /** OPAQUE and CUTOUT materials. Depth written, no blending. */
        SOLID,
        /** BLEND materials. Depth tested, not written, back-to-front. */
        BLEND
    }

    private final HgScene scene;
    private final MeshManager meshes;
    private final MaterialManager materials;
    private final ProbeManager probes;

    private final ObjectArrayList<ModelInstance> visible = new ObjectArrayList<>();
    private final InstanceBuffer instanceBuffer = new InstanceBuffer(2048);
    private final GlStateGuard guard = new GlStateGuard();

    private final Map<BatchKey, Batch> batchIndex = new HashMap<>();
    private final ObjectArrayList<Batch> batchPool = new ObjectArrayList<>();
    private final ObjectArrayList<Batch> activeBatches = new ObjectArrayList<>();
    private int poolCursor;

    private final Matrix4f modelMatrix = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f viewEyeScratch = new Matrix4f();

    /** Camera the pass currently being drawn renders from; see applyProbe. */
    private Vec3 passCameraPos = Vec3.ZERO;

    @Nullable
    private ShaderProgram pbr;

    // Frame statistics, surfaced in the F3 overlay.
    private int statInstances;
    private int statBatches;
    private int statDrawCalls;
    private long statTriangles;

    public HgRenderer(HgScene scene, MeshManager meshes, MaterialManager materials,
                      ProbeManager probes) {
        this.scene = scene;
        this.meshes = meshes;
        this.materials = materials;
        this.probes = probes;
    }

    // ------------------------------------------------------------------ lifecycle
    public void reload(ResourceManager resources) {
        if (this.pbr != null) {
            this.pbr.close();
        }
        this.pbr = ShaderProgram.compileOrNull(resources, "hg_pbr",
                Hypergraphical.id("shaders/hg/pbr.vsh"),
                Hypergraphical.id("shaders/hg/pbr.fsh"),
                Map.of());
        if (this.pbr == null) {
            Hypergraphical.LOGGER.error("[hypergraphical] PBR shader failed to compile; "
                    + "nothing will be drawn until it does.");
        }
    }

    public boolean ready() {
        return this.pbr != null && this.pbr.valid();
    }

    /**
     * Per-frame residency work: drains the mesh and texture upload queues. Called once at the
     * start of a frame, outside any render pass, so a large upload cannot land halfway through
     * a draw sequence.
     */
    public void beginFrame() {
        this.meshes.processUploads(2);
        this.materials.textures().processUploads(4);
    }

    // --------------------------------------------------------------------- drawing
    /**
     * Draws one pass.
     *
     * @param viewMatrix  camera rotation from the event's pose stack; Minecraft's view matrix
     *                    carries no translation because it already renders camera-relative
     * @param frustum     event frustum, or {@code null} to skip frustum culling
     */
    public void render(Pass pass, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       Vec3 cameraPos, @Nullable Frustum frustum, float partialTick) {
        if (!HgConfig.ENABLED.get() || !ready() || this.scene.size() == 0) {
            return;
        }
        ShaderProgram shader = this.pbr;
        if (shader == null) {
            return;
        }

        this.passCameraPos = cameraPos;
        boolean forProbeCapture = this.probes.capturing();

        // The visible set and probe assignment are shared by both passes, so only the first
        // pass of a frame pays for them. During a probe capture the camera is different, so
        // it is recomputed there too.
        if (pass == Pass.SOLID || forProbeCapture) {
            this.scene.gatherVisible(cameraPos, frustum, forProbeCapture, this.visible);
            for (int i = 0; i < this.visible.size(); i++) {
                this.probes.assignProbes(this.visible.get(i));
            }
        }
        if (this.visible.isEmpty()) {
            return;
        }

        buildBatches(pass, cameraPos);
        if (this.activeBatches.isEmpty()) {
            return;
        }

        this.instanceBuffer.upload();

        this.viewProjection.set(projectionMatrix).mul(viewMatrix);

        this.guard.capture();
        try {
            applyPassState(pass);
            shader.bind();
            setGlobalUniforms(shader, pass, cameraPos, partialTick);
            drawBatches(shader, pass);
        } finally {
            ShaderProgram.unbind();
            unbindTextures();
            this.guard.restore();
            Gl.check("HgRenderer.render " + pass);
        }
    }

    private void applyPassState(Pass pass) {
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL_LEQUAL);
        if (pass == Pass.SOLID) {
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        } else {
            // Blended geometry must not write depth, or two transparent surfaces in the same
            // batch would occlude each other in submission order.
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA,
                    GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        }
        RenderSystem.enableCull();
    }

    // -------------------------------------------------------------------- batching
    private record BatchKey(GpuMesh mesh, int subMeshIndex, HgMaterial material,
                            int probeA, int probeB) {}

    private static final class Batch {
        GpuMesh mesh;
        GpuMesh.SubMesh subMesh;
        HgMaterial material;
        int probeA;
        int probeB;
        int firstInstance;
        int instanceCount;
        double sortDepth;
        final ObjectArrayList<ModelInstance> instances = new ObjectArrayList<>();

        void reset() {
            this.instances.clear();
            this.instanceCount = 0;
            this.sortDepth = 0.0D;
        }
    }

    private Batch acquireBatch() {
        if (this.poolCursor < this.batchPool.size()) {
            Batch batch = this.batchPool.get(this.poolCursor++);
            batch.reset();
            return batch;
        }
        Batch batch = new Batch();
        this.batchPool.add(batch);
        this.poolCursor++;
        return batch;
    }

    private void buildBatches(Pass pass, Vec3 cameraPos) {
        this.batchIndex.clear();
        this.activeBatches.clear();
        this.poolCursor = 0;

        final boolean wantBlend = pass == Pass.BLEND;

        for (int i = 0, n = this.visible.size(); i < n; i++) {
            ModelInstance instance = this.visible.get(i);
            GpuMesh mesh = this.meshes.get(instance.mesh());
            if (mesh == null) {
                continue; // Still loading; it will appear in a frame or two.
            }
            instance.setLocalBoundsRadius(mesh.boundingRadius());

            java.util.List<GpuMesh.SubMesh> subMeshes = mesh.subMeshes();
            for (int s = 0; s < subMeshes.size(); s++) {
                GpuMesh.SubMesh subMesh = subMeshes.get(s);
                if (!instance.isPartVisible(subMesh.part())) {
                    continue;
                }
                HgMaterial material = this.materials.resolve(
                        instance.materialFor(subMesh.part(), subMesh.name()),
                        subMesh.autoMaterial());
                if (material.blended() != wantBlend) {
                    continue;
                }

                BatchKey key = new BatchKey(mesh, s, material,
                        instance.probeA(), instance.probeB());
                Batch batch = this.batchIndex.get(key);
                if (batch == null) {
                    batch = acquireBatch();
                    batch.mesh = mesh;
                    batch.subMesh = subMesh;
                    batch.material = material;
                    batch.probeA = instance.probeA();
                    batch.probeB = instance.probeB();
                    this.batchIndex.put(key, batch);
                    this.activeBatches.add(batch);
                }
                batch.instances.add(instance);
            }
        }

        if (wantBlend) {
            sortForBlending(cameraPos);
        }
        writeInstanceData(cameraPos);
    }

    /**
     * Orders blended geometry back to front.
     *
     * <p>Instances are sorted within each batch and batches are sorted by their farthest
     * instance. That is not a globally correct depth sort &mdash; two interleaved batches can
     * still resolve in the wrong order &mdash; and making it exact would mean one draw call per
     * instance, which throws away the entire point of instancing. Every instanced renderer
     * makes this trade; the visible cases are coplanar transparent surfaces of different
     * materials, which is rare in practice.
     */
    private void sortForBlending(Vec3 cameraPos) {
        for (int b = 0; b < this.activeBatches.size(); b++) {
            Batch batch = this.activeBatches.get(b);
            batch.instances.sort((left, right) -> Double.compare(
                    distanceSq(right, cameraPos), distanceSq(left, cameraPos)));
            batch.sortDepth = batch.instances.isEmpty() ? 0.0D
                    : distanceSq(batch.instances.get(0), cameraPos);
        }
        this.activeBatches.sort((left, right) -> Double.compare(right.sortDepth, left.sortDepth));
    }

    private static double distanceSq(ModelInstance instance, Vec3 cameraPos) {
        double dx = instance.x() - cameraPos.x;
        double dy = instance.y() - cameraPos.y;
        double dz = instance.z() - cameraPos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void writeInstanceData(Vec3 cameraPos) {
        this.instanceBuffer.begin();
        this.statInstances = 0;
        this.statBatches = this.activeBatches.size();

        for (int b = 0; b < this.activeBatches.size(); b++) {
            Batch batch = this.activeBatches.get(b);
            batch.firstInstance = this.instanceBuffer.count();

            HgMaterial material = batch.material;
            for (int i = 0; i < batch.instances.size(); i++) {
                ModelInstance instance = batch.instances.get(i);

                // Camera-relative translation in double, narrowed to float only after the
                // subtraction. Building a world-space float matrix instead would visibly jitter
                // geometry a few thousand blocks out.
                this.modelMatrix.set(instance.localMatrix());
                this.modelMatrix.m30((float) (instance.x() - cameraPos.x));
                this.modelMatrix.m31((float) (instance.y() - cameraPos.y));
                this.modelMatrix.m32((float) (instance.z() - cameraPos.z));

                // LightTexture#block/#sky already shift the packed light back down to the
                // 0..15 light level the shader wants, so they need no further scaling.
                int packedLight = HgScene.lightOf(instance);
                float lightBlock = LightTexture.block(packedLight);
                float lightSky = LightTexture.sky(packedLight);

                float metallic = Float.isNaN(instance.metallic())
                        ? material.metallic : instance.metallic();
                float smoothness = Float.isNaN(instance.smoothness())
                        ? material.smoothness : instance.smoothness();
                float emissive = Float.isNaN(instance.emissiveStrength())
                        ? material.emissiveStrength : instance.emissiveStrength();

                // Batches are per sub-mesh, so a per-part or per-element glow is just a lookup
                // here. A glowing surface also lifts its block light towards 15, so the
                // lightmap term stops darkening a lamp that is supposed to be lit.
                float glow = instance.glowFor(batch.subMesh.part(), batch.subMesh.name());
                if (glow > 0.0F) {
                    lightBlock += (15.0F - lightBlock) * Math.min(1.0F, glow);
                }

                this.instanceBuffer.push(this.modelMatrix,
                        instance.tint().x, instance.tint().y, instance.tint().z, instance.tint().w,
                        metallic, smoothness, emissive, instance.probeBlend(),
                        lightBlock, lightSky, instance.uvOffset().x, instance.uvOffset().y,
                        instance.occlusionStrength() * material.occlusion, glow);
            }

            batch.instanceCount = batch.instances.size();
            this.statInstances += batch.instanceCount;
        }
    }

    // --------------------------------------------------------------------- uniforms
    private void setGlobalUniforms(ShaderProgram shader, Pass pass, Vec3 cameraPos,
                                   float partialTick) {
        shader.set("uViewProjection", this.viewProjection);

        setViewEye(shader);

        float[] fog = RenderSystem.getShaderFogColor();
        shader.set("uFogColor", fog[0], fog[1], fog[2], fog[3]);
        shader.set("uFogStart", HgConfig.APPLY_FOG.get()
                ? RenderSystem.getShaderFogStart() : Float.MAX_VALUE);
        shader.set("uFogEnd", HgConfig.APPLY_FOG.get()
                ? RenderSystem.getShaderFogEnd() : Float.MAX_VALUE);
        shader.set("uFogSpherical",
                RenderSystem.getShaderFogShape() == com.mojang.blaze3d.shaders.FogShape.SPHERE);

        setSunUniforms(shader, partialTick);

        shader.set("uIblDiffuse", HgConfig.IBL_DIFFUSE.get().floatValue());
        shader.set("uIblSpecular", HgConfig.IBL_SPECULAR.get().floatValue());
        shader.set("uBoxProjection", HgConfig.PROBE_PARALLAX_BOX.get());
        shader.set("uBlendPass", pass == Pass.BLEND);

        // Every irradiance cubemap is the same fixed size, so its top mip index is constant.
        // The shader reads that mip as the map's global average.
        shader.set("uProbeIrradianceMaxMip",
                (float) (31 - Integer.numberOfLeadingZeros(IblProcessor.IRRADIANCE_SIZE)));

        // Samplers never move, so these are set once and cached by ShaderProgram.
        shader.sampler("uAlbedoMap", UNIT_ALBEDO);
        shader.sampler("uEmissiveMap", UNIT_EMISSIVE);
        shader.sampler("uMetallicMap", UNIT_METALLIC);
        shader.sampler("uRoughnessMap", UNIT_ROUGHNESS);
        shader.sampler("uLightMap", UNIT_LIGHTMAP);
        shader.sampler("uBrdfLut", UNIT_BRDF);
        shader.sampler("uProbeRadianceA", UNIT_PROBE_A_RADIANCE);
        shader.sampler("uProbeRadianceB", UNIT_PROBE_B_RADIANCE);
        shader.sampler("uProbeIrradianceA", UNIT_PROBE_A_IRRADIANCE);
        shader.sampler("uProbeIrradianceB", UNIT_PROBE_B_IRRADIANCE);

        bindLightmap();
        bindBrdfLut();
    }

    /**
     * Tells the shader where the eye is, in a form that works for any projection.
     *
     * <p>The eye is the one point every view ray passes through. For a perspective projection
     * that is a real point (the camera, which in our camera-relative space is the origin, but
     * a probe capture or a mod's projection may move it); for an orthographic projection the
     * rays are parallel and the "point" is at infinity in the direction opposite the view.
     * Both cases are the null space of the view-projection matrix: the homogeneous point
     * {@code E} with {@code VP * E = (0, 0, z, 0)}, which is {@code inverse(VP) * (0, 0, 1, 0)}.
     * The shader then computes the per-fragment view vector as
     * {@code normalize(E.xyz - fragPos * E.w)}: {@code eye - fragPos} when {@code w = 1}, the
     * constant direction towards the camera when {@code w = 0}, and the right thing for any
     * blend of the two (a projection animating from perspective to orthographic).
     *
     * <p>The scale and sign of {@code E} are arbitrary, so it is normalised here: to
     * {@code w = 1} when the eye is finite, otherwise to a unit vector pointing against the
     * view direction (taken from the near and far points of the screen centre).
     */
    private void setViewEye(ShaderProgram shader) {
        Matrix4f inverse = this.viewEyeScratch.set(this.viewProjection);
        if (!Float.isFinite(inverse.determinant()) || inverse.determinant() == 0.0F) {
            shader.set("uViewEye", 0.0F, 0.0F, 0.0F, 1.0F);
            return;
        }
        inverse.invert();
        org.joml.Vector4f eye = inverse.transform(new org.joml.Vector4f(0.0F, 0.0F, 1.0F, 0.0F));

        float eyeLength = (float) Math.sqrt(eye.x * eye.x + eye.y * eye.y + eye.z * eye.z);
        if (Math.abs(eye.w) > 1.0E-6F * Math.max(1.0F, eyeLength)) {
            shader.set("uViewEye", eye.x / eye.w, eye.y / eye.w, eye.z / eye.w, 1.0F);
            return;
        }

        // Orthographic: the screen centre's far point minus its near point is the view
        // direction; the eye direction is its opposite.
        org.joml.Vector4f near = inverse.transform(new org.joml.Vector4f(0.0F, 0.0F, -1.0F, 1.0F));
        org.joml.Vector4f far = inverse.transform(new org.joml.Vector4f(0.0F, 0.0F, 1.0F, 1.0F));
        float fx = far.x / far.w - near.x / near.w;
        float fy = far.y / far.w - near.y / near.w;
        float fz = far.z / far.w - near.z / near.w;
        float length = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (!(length > 0.0F) || !Float.isFinite(length)) {
            shader.set("uViewEye", 0.0F, 0.0F, 0.0F, 1.0F);
            return;
        }
        shader.set("uViewEye", -fx / length, -fy / length, -fz / length, 0.0F);
    }

    private void setSunUniforms(ShaderProgram shader, float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !HgConfig.SUN_SPECULAR.get()) {
            shader.set("uSunIntensity", 0.0F);
            shader.set("uSunDirection", 0.0F, 1.0F, 0.0F);
            shader.set("uSunColor", 1.0F, 1.0F, 1.0F);
            return;
        }

        // Same construction LevelRenderer#renderSky uses to place the sun disc, so our
        // highlight lands where the visible sun actually is.
        float timeOfDay = level.getTimeOfDay(partialTick);
        double angle = timeOfDay * (Math.PI * 2.0D);
        float sunX = (float) -Math.sin(angle);
        float sunY = (float) Math.cos(angle);

        // Below the horizon the moon takes over, on the opposite side of the sky.
        boolean night = sunY < 0.0F;
        float elevation = Math.abs(sunY);
        float intensity = Math.min(1.0F, elevation * 1.5F);
        if (night) {
            // The moon gets a fixed fraction rather than being scaled by daylight, which is
            // zero at night and would remove the highlight altogether.
            intensity *= 0.15F;
        } else {
            // ClientLevel#getSkyDarken is a daylight factor (1 at noon, 0 at night) and folds
            // in weather and dimension darkening, so the highlight fades with the sky.
            intensity *= level.getSkyDarken(partialTick);
        }

        shader.set("uSunDirection", night ? -sunX : sunX, night ? -sunY : sunY, 0.0F);
        shader.set("uSunIntensity", Math.max(0.0F, intensity));
        // A cool tint at night reads as moonlight without needing a second light.
        if (night) {
            shader.set("uSunColor", 0.55F, 0.65F, 1.0F);
        } else {
            shader.set("uSunColor", 1.0F, 0.96F, 0.88F);
        }
    }

    private void bindLightmap() {
        Minecraft minecraft = Minecraft.getInstance();
        LightTexture lightTexture = minecraft.gameRenderer.lightTexture();
        // turnOnLightLayer resolves the dynamic light texture into shader slot 2; reading it
        // back is the only public way to get its GL id.
        int previousSlot2 = RenderSystem.getShaderTexture(2);
        lightTexture.turnOnLightLayer();
        int lightmapId = RenderSystem.getShaderTexture(2);
        RenderSystem.setShaderTexture(2, previousSlot2);

        GlStateManager._activeTexture(GL_TEXTURE0 + UNIT_LIGHTMAP);
        GlStateManager._bindTexture(lightmapId);
    }

    private void bindBrdfLut() {
        GlStateManager._activeTexture(GL_TEXTURE0 + UNIT_BRDF);
        GlStateManager._bindTexture(this.probes.ibl().brdfLut());
    }

    // ---------------------------------------------------------------------- drawing
    private void drawBatches(ShaderProgram shader, Pass pass) {
        this.statDrawCalls = 0;
        this.statTriangles = 0L;

        HgMaterial lastMaterial = null;
        int lastProbeA = Integer.MIN_VALUE;
        int lastProbeB = Integer.MIN_VALUE;
        GpuMesh lastMesh = null;
        boolean lastDoubleSided = false;

        for (int b = 0; b < this.activeBatches.size(); b++) {
            Batch batch = this.activeBatches.get(b);
            if (batch.instanceCount == 0) {
                continue;
            }

            if (batch.material != lastMaterial) {
                applyMaterial(shader, batch.material);
                if (batch.material.doubleSided != lastDoubleSided) {
                    lastDoubleSided = batch.material.doubleSided;
                    if (lastDoubleSided) {
                        RenderSystem.disableCull();
                    } else {
                        RenderSystem.enableCull();
                    }
                }
                lastMaterial = batch.material;
            }

            if (batch.probeA != lastProbeA || batch.probeB != lastProbeB) {
                applyProbes(shader, batch.probeA, batch.probeB);
                lastProbeA = batch.probeA;
                lastProbeB = batch.probeB;
            }

            if (batch.mesh != lastMesh) {
                batch.mesh.bind();
                lastMesh = batch.mesh;
            }
            this.instanceBuffer.bindAttributes(batch.firstInstance);

            batch.mesh.drawInstanced(batch.subMesh, batch.instanceCount);
            this.statDrawCalls++;
            this.statTriangles += (long) (batch.subMesh.indexCount() / 3) * batch.instanceCount;
        }

        GlStateManager._glBindVertexArray(0);
    }

    private void applyMaterial(ShaderProgram shader, HgMaterial material) {
        TextureBank textures = this.materials.textures();

        bind2d(UNIT_ALBEDO, textures.get(material.albedoMap, textures.white()));
        bind2d(UNIT_EMISSIVE, textures.get(material.emissiveMap, textures.white()));
        bind2d(UNIT_METALLIC, textures.get(material.metallicMap, textures.white()));
        bind2d(UNIT_ROUGHNESS, textures.get(material.roughnessMap, textures.white()));

        shader.set("uTiling", material.tiling.x, material.tiling.y);
        shader.set("uOffset", material.offset.x, material.offset.y);
        shader.set("uMaterialTint", material.tint.x, material.tint.y,
                material.tint.z, material.tint.w);
        shader.set("uEmissiveColor", material.emissiveColor.x, material.emissiveColor.y,
                material.emissiveColor.z);
        shader.set("uAlphaCutoff",
                material.alphaMode == HgMaterial.AlphaMode.CUTOUT ? material.alphaCutoff : 0.0F);
        shader.set("uHasEmissiveMap", material.emissiveMap != null);
        shader.set("uHasMetallicMap", material.metallicMap != null);
        shader.set("uHasRoughnessMap", material.roughnessMap != null);
        shader.set("uPackedMetallicRoughness", material.packedMetallicRoughness);
        shader.set("uReflectivity", material.reflectivity);
        shader.set("uSunSpecularScale", material.sunSpecular);
    }

    private void applyProbes(ShaderProgram shader, int probeIdA, int probeIdB) {
        ReflectionProbe probeA = probeIdA >= 0 ? this.probes.byId(probeIdA) : null;
        ReflectionProbe probeB = probeIdB >= 0 ? this.probes.byId(probeIdB) : null;

        applyProbe(shader, probeA, "A", UNIT_PROBE_A_RADIANCE, UNIT_PROBE_A_IRRADIANCE);
        applyProbe(shader, probeB, "B", UNIT_PROBE_B_RADIANCE, UNIT_PROBE_B_IRRADIANCE);
    }

    private void applyProbe(ShaderProgram shader, @Nullable ReflectionProbe probe, String slot,
                            int radianceUnit, int irradianceUnit) {
        if (probe == null || probe.prefilteredTexture() == 0) {
            shader.set("uHasProbe" + slot, false);
            bindCubemap(radianceUnit, 0);
            bindCubemap(irradianceUnit, 0);
            return;
        }
        shader.set("uHasProbe" + slot, true);
        shader.set("uProbeMaxMip" + slot, (float) (probe.prefilteredMipLevels() - 1));

        // Probe geometry goes camera-relative to match vertex positions. This must be the
        // camera the current pass is rendering from, which during a probe capture is the
        // capture camera and not Minecraft's main camera.
        Vec3 cameraPos = this.passCameraPos;
        Vec3 center = probe.center();
        shader.set("uProbeCenter" + slot,
                (float) (center.x - cameraPos.x),
                (float) (center.y - cameraPos.y),
                (float) (center.z - cameraPos.z));
        Vec3 extents = probe.halfExtents();
        shader.set("uProbeExtents" + slot,
                (float) extents.x, (float) extents.y, (float) extents.z);

        bindCubemap(radianceUnit, probe.prefilteredTexture());
        bindCubemap(irradianceUnit, probe.irradianceTexture());
    }

    private static void bind2d(int unit, int texture) {
        GlStateManager._activeTexture(GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture);
    }

    private static void bindCubemap(int unit, int texture) {
        // Blaze3D tracks GL_TEXTURE_2D bindings only, so cubemaps go through raw GL without
        // desyncing its cache.
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_CUBE_MAP, texture);
    }

    private void unbindTextures() {
        for (int unit = UNIT_ALBEDO; unit <= UNIT_BRDF; unit++) {
            bind2d(unit, 0);
        }
        for (int unit = UNIT_PROBE_A_RADIANCE; unit <= UNIT_PROBE_B_IRRADIANCE; unit++) {
            bindCubemap(unit, 0);
        }
        GlStateManager._activeTexture(GL_TEXTURE0);
    }

    // ------------------------------------------------------------------- statistics
    public int lastInstanceCount() {
        return this.statInstances;
    }

    public int lastBatchCount() {
        return this.statBatches;
    }

    public int lastDrawCalls() {
        return this.statDrawCalls;
    }

    public long lastTriangles() {
        return this.statTriangles;
    }

    public int visibleCount() {
        return this.visible.size();
    }

    @Override
    public void close() {
        if (this.pbr != null) {
            this.pbr.close();
            this.pbr = null;
        }
        this.instanceBuffer.close();
        this.batchPool.clear();
        this.activeBatches.clear();
        this.batchIndex.clear();
    }

    /** Exposed so the debug overlay can report which IBL pieces exist. */
    public IblProcessor ibl() {
        return this.probes.ibl();
    }
}
