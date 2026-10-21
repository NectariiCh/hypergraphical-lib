package dev.nectariich.hypergraphical.api;

import dev.nectariich.hypergraphical.HgConfig;
import dev.nectariich.hypergraphical.Hypergraphical;
import dev.nectariich.hypergraphical.mesh.GpuMesh;
import dev.nectariich.hypergraphical.mesh.MeshBounds;
import dev.nectariich.hypergraphical.mesh.MeshManager;
import dev.nectariich.hypergraphical.mesh.RuntimeAssets;
import dev.nectariich.hypergraphical.mesh.obj.ObjMeshReader;
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

    // ------------------------------------------------------------ runtime files
    /**
     * Registers a file under a resource location, ahead of the resource packs. Register an
     * {@code .obj} together with its {@code .mtl} and textures, at the locations its
     * {@code mtllib} and {@code map_*} lines resolve to, and then spawn it like any pack mesh.
     *
     * <p>This is how a model that is not in any pack gets drawn: one a player imported, or one
     * a server sent. Re-registering a location replaces the file but not what was already
     * uploaded from it; call {@link #removeRuntimeFiles} first to reload.
     */
    public static void registerRuntimeFile(ResourceLocation location, byte[] bytes) {
        RuntimeAssets.put(location, bytes);
    }

    /**
     * Removes every runtime file under a path prefix and drops whatever was uploaded from
     * them. Instances still pointing at a removed mesh simply stop drawing.
     */
    public static void removeRuntimeFiles(String namespace, String pathPrefix) {
        for (ResourceLocation location : RuntimeAssets.removeUnder(namespace, pathPrefix)) {
            if (location.getPath().endsWith(".obj")) {
                HypergraphicalClient.meshes().evict(location);
            } else {
                HypergraphicalClient.materials().textures().evict(location);
            }
        }
    }

    // --------------------------------------------------------- ambient occlusion
    /**
     * Opts meshes into baked ambient occlusion: every mesh whose location is in
     * {@code namespace} and whose path starts with {@code pathPrefix} (runtime files and
     * resource-pack meshes alike) gets per-vertex occlusion baked on a background thread
     * after it uploads. The mesh draws un-occluded until its bake lands, usually well under a
     * second later, and never stalls a frame. Meshes that match no rule read occlusion 1
     * everywhere and look exactly as before.
     *
     * <p>Register before spawning the meshes it covers. Meshes already resident under the
     * prefix are dropped and reload with the new setting, so they blink out for a frame.
     * When several rules match, the longest prefix wins.
     *
     * <pre>{@code
     * HypergraphicalAPI.setOcclusionBaking("mymod", "props/", OcclusionSettings.STRONG);
     * }</pre>
     *
     * @param settings how to bake, or {@code null} to remove the rule for this prefix
     * @see ModelInstance#setOcclusionStrength(float)
     */
    public static void setOcclusionBaking(String namespace, String pathPrefix,
                                          @Nullable OcclusionSettings settings) {
        HypergraphicalClient.meshes().setOcclusionBaking(namespace, pathPrefix, settings);
    }

    /**
     * Makes {@code variant} a mesh location of its own whose geometry, materials and
     * textures are read from {@code source}. Spawn it like any mesh. It is resident and
     * baked separately from the source, and occlusion rules match it by its own location,
     * so this is how one model is drawn with two different occlusion bakes at once:
     *
     * <pre>{@code
     * HypergraphicalAPI.setOcclusionBaking("mymod", "soft_ao/", new OcclusionSettings(32, 0.6F, 1.0F));
     * HypergraphicalAPI.registerMeshVariant(new ResourceLocation("mymod", "soft_ao/statue.obj"), statue);
     * }</pre>
     *
     * <p>The variant needs no files of its own, and since its materials and textures are the
     * source's, they are not loaded a second time. Removing or replacing the source's runtime
     * files drops the variant's upload too, and it reloads from the new files on next use.
     */
    public static void registerMeshVariant(ResourceLocation variant, ResourceLocation source) {
        HypergraphicalClient.meshes().registerVariant(variant, source);
    }

    /** Forgets a variant registered with {@link #registerMeshVariant} and frees its upload. */
    public static void removeMeshVariant(ResourceLocation variant) {
        HypergraphicalClient.meshes().removeVariant(variant);
    }

    /** Whether a resident mesh has its baked occlusion applied. */
    public static boolean isOcclusionBaked(ResourceLocation mesh) {
        GpuMesh resident = HypergraphicalClient.meshes().get(mesh);
        return resident != null && resident.occlusionBaked();
    }

    // ---------------------------------------------------------------- inspection
    /**
     * Parses a mesh on the calling thread and reports its bounds and parts, without uploading
     * anything. For sizing a model before it is placed, e.g. in an import screen. Parsing is
     * the expensive half of a load, so keep this off the frame loop for large models.
     *
     * @return {@code null} if the mesh does not exist or could not be read
     */
    @Nullable
    public static MeshInfo inspectMesh(ResourceLocation mesh) {
        try {
            ObjMeshReader.Result result = MeshManager.parse(mesh);
            if (result == null) {
                return null;
            }
            return MeshInfo.of(result.mesh());
        } catch (Exception e) {
            Hypergraphical.LOGGER.warn("[hypergraphical] Could not inspect {}: {}", mesh, e.toString());
            return null;
        }
    }

    /** Bounds of a resident mesh, or {@code null} while it is not on the GPU yet. */
    @Nullable
    public static MeshBounds meshBounds(ResourceLocation mesh) {
        GpuMesh resident = HypergraphicalClient.meshes().get(mesh);
        return resident == null ? null : resident.bounds();
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

    /** One material element of a mesh, by its part and its {@code usemtl} name. */
    public record ElementRef(String part, String material) {}

    /**
     * Every material element of a resident mesh, in draw order, or {@code null} while the mesh
     * is not on the GPU yet. The resident counterpart of {@link MeshInfo#elements()}, for
     * applying {@link ModelInstance#setElementMaterial} overrides without parsing the mesh.
     */
    @Nullable
    public static List<ElementRef> residentElements(ResourceLocation mesh) {
        GpuMesh resident = HypergraphicalClient.meshes().get(mesh);
        if (resident == null) {
            return null;
        }
        List<ElementRef> out = new java.util.ArrayList<>(resident.subMeshes().size());
        for (GpuMesh.SubMesh sub : resident.subMeshes()) {
            out.add(new ElementRef(sub.part(), sub.name()));
        }
        return out;
    }

    /**
     * The material one element of a resident mesh draws with when nothing overrides it: the
     * faces of {@code part} using the {@code usemtl} material {@code material}. {@code null}
     * while the mesh is not on the GPU yet, or when it has no such element.
     */
    @Nullable
    public static HgMaterial elementMaterial(ResourceLocation mesh, String part, String material) {
        GpuMesh resident = HypergraphicalClient.meshes().get(mesh);
        if (resident == null) {
            return null;
        }
        for (GpuMesh.SubMesh sub : resident.subMeshes()) {
            if (sub.part().equals(part) && sub.name().equals(material)) {
                return HypergraphicalClient.materials().resolve(null, sub.autoMaterial());
            }
        }
        return null;
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
