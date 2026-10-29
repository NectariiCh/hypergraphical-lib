package dev.nectariich.hypergraphical.mesh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nectariich.hypergraphical.Hypergraphical;
import dev.nectariich.hypergraphical.api.OcclusionSettings;
import dev.nectariich.hypergraphical.material.HgMaterial;
import dev.nectariich.hypergraphical.material.MaterialManager;
import dev.nectariich.hypergraphical.mesh.obj.MtlLibrary;
import dev.nectariich.hypergraphical.mesh.obj.ObjImportSettings;
import dev.nectariich.hypergraphical.mesh.obj.ObjMeshReader;
import dev.nectariich.hypergraphical.util.HgIds;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mesh residency: turns a {@code .obj} resource path into a {@link GpuMesh}.
 *
 * <p>Parsing runs on a background thread and the GL upload runs on the render thread, for the
 * same reason as textures but more so: welding, normal generation and tangent generation on a
 * 100k-triangle model is tens of milliseconds, and doing that inline would be a hard stall
 * every time a model came into view.
 *
 * <p>Requests are fire-and-forget. {@link #get} returns {@code null} while a mesh is still in
 * flight and the renderer simply skips that instance for a frame or two.
 *
 * <h2>Baked ambient occlusion</h2>
 *
 * <p>Meshes whose location matches a rule registered through {@link #setOcclusionBaking} get
 * per-vertex occlusion baked by {@link OcclusionBaker}. The mesh uploads first, un-occluded,
 * and the bake starts at that moment on a dedicated low-priority pool; when it finishes its
 * result is queued and patched into the mesh's occlusion buffer by the next
 * {@link #processUploads}. So a model appears immediately and darkens in its crevices a moment
 * later, and no frame ever waits for a bake. Results are cached by location, settings and
 * geometry, so a resource reload or an evict-and-reload of an unchanged mesh reapplies the
 * old bake a frame after upload instead of casting every ray again.
 *
 * <h2>Variants</h2>
 *
 * <p>A variant ({@link #registerVariant}) is a second resident copy of a mesh under a
 * location of its own, read from another mesh's files. Residency, occlusion rules and the
 * bake cache all go by location, so a variant is how one model is baked two ways at once.
 * Its geometry, materials and textures are read from the source, under the source's name,
 * so the auto materials and textures are the source's own and are not loaded twice.
 */
public final class MeshManager implements ResourceManagerReloadListener {

    private final MaterialManager materials;

    private final Map<ResourceLocation, GpuMesh> resident = new ConcurrentHashMap<>();
    private final Set<ResourceLocation> pending = ConcurrentHashMap.newKeySet();
    private final Set<ResourceLocation> failed = ConcurrentHashMap.newKeySet();
    private final Queue<Parsed> uploadQueue = new ConcurrentLinkedQueue<>();

    /** Variant location to the location of the mesh whose files it is read from. */
    private final Map<ResourceLocation, ResourceLocation> variants = new ConcurrentHashMap<>();

    private record Parsed(ResourceLocation id, ObjMeshReader.Result result, boolean emissiveAmbient) {}

    /** One occlusion rule: meshes under {@code namespace:pathPrefix} bake with {@code settings}. */
    private record OcclusionRule(String namespace, String pathPrefix, OcclusionSettings settings) {}

    /** A bake in flight, cancelled when its mesh is evicted, replaced or cleared. */
    private record BakeJob(GpuMesh target, AtomicBoolean cancelled) {}

    /** A finished bake waiting for the render thread. */
    private record BakeResult(ResourceLocation id, GpuMesh target, float[] occlusion) {}

    /** A cached bake; reused only when the settings and the geometry both still match. */
    private record CachedBake(OcclusionSettings settings, int geometryHash, float[] occlusion) {}

    /** Most bakes kept for reuse. A bake is 4 bytes per vertex, so this is a few megabytes. */
    private static final int BAKE_CACHE_SIZE = 64;

    private final List<OcclusionRule> occlusionRules = new CopyOnWriteArrayList<>();
    private final Map<ResourceLocation, BakeJob> bakes = new ConcurrentHashMap<>();
    private final Queue<BakeResult> bakeResults = new ConcurrentLinkedQueue<>();
    private final Map<ResourceLocation, CachedBake> bakeCache = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ResourceLocation, CachedBake> eldest) {
            return size() > BAKE_CACHE_SIZE;
        }
    };

    /**
     * The bake pool. Separate from Minecraft's background executor so a long bake never holds
     * up resource loading or world generation work queued there, with half the cores so the
     * game keeps the rest, and minimum-priority daemon threads so the OS prefers the render
     * thread whenever they compete.
     */
    @Nullable
    private static volatile ForkJoinPool bakePool;

    public MeshManager(MaterialManager materials) {
        this.materials = materials;
    }

    /**
     * Normalises a mesh id so callers can pass either {@code ns:models/obj/foo} or
     * {@code ns:models/obj/foo.obj}.
     */
    /**
     * Every {@code .obj} the resource packs currently offer, for command suggestions.
     *
     * <p>Scans the packs rather than reporting what happens to be resident, so a model that
     * has never been spawned still shows up.
     */
    public static java.util.Set<ResourceLocation> available() {
        java.util.Set<ResourceLocation> found = new java.util.TreeSet<>(
                java.util.Comparator.comparing(ResourceLocation::toString));
        try {
            found.addAll(Minecraft.getInstance().getResourceManager()
                    .listResources("models/obj", path -> path.getPath().endsWith(".obj"))
                    .keySet());
        } catch (Exception e) {
            Hypergraphical.LOGGER.warn("[hypergraphical] Could not list meshes: {}", e.toString());
        }
        return found;
    }

    /** Whether a mesh exists, as a runtime file or a resource, without triggering a load. */
    public static boolean exists(ResourceLocation rawId) {
        return RuntimeAssets.exists(normalize(rawId));
    }

    public static ResourceLocation normalize(ResourceLocation id) {
        return id.getPath().endsWith(".obj") ? id
                : HgIds.of(id.getNamespace(), id.getPath() + ".obj");
    }

    /**
     * The mesh if it is resident, otherwise {@code null} with a background load queued.
     */
    @Nullable
    public GpuMesh get(ResourceLocation rawId) {
        ResourceLocation id = normalize(rawId);
        GpuMesh mesh = this.resident.get(id);
        if (mesh != null) {
            return mesh;
        }
        if (!this.failed.contains(id)) {
            requestLoad(id);
        }
        return null;
    }

    /** Starts loading a mesh without needing it this frame. */
    public void preload(ResourceLocation rawId) {
        get(rawId);
    }

    private void requestLoad(ResourceLocation id) {
        if (!this.pending.add(id)) {
            return;
        }
        // A variant reads its source's files, under the source's name, and is resident
        // under its own
        ResourceLocation source = this.variants.getOrDefault(id, id);
        Util.backgroundExecutor().execute(() -> {
            try {
                ObjMeshReader.Result result = parse(source);
                if (result == null) {
                    Hypergraphical.LOGGER.warn("[hypergraphical] Mesh not found: {}", id);
                    this.failed.add(id);
                    this.pending.remove(id);
                    return;
                }
                ObjImportSettings settings = readSettings(source);

                if (result.mesh().triangleCount() == 0) {
                    Hypergraphical.LOGGER.warn("[hypergraphical] Mesh {} has no triangles", id);
                    this.failed.add(id);
                    this.pending.remove(id);
                    return;
                }

                Hypergraphical.LOGGER.debug(
                        "[hypergraphical] Parsed {}: {} verts, {} tris, {} sub-mesh(es)",
                        id, result.mesh().vertexCount(), result.mesh().triangleCount(),
                        result.mesh().subMeshes().size());

                this.uploadQueue.add(new Parsed(id, result, settings.emissiveAmbient()));
            } catch (Exception e) {
                Hypergraphical.LOGGER.error("[hypergraphical] Failed to load mesh {}", id, e);
                this.failed.add(id);
                this.pending.remove(id);
            }
        });
    }

    /**
     * Parses a mesh on the calling thread, from a runtime file or a resource, without touching
     * GL. {@code null} when the mesh does not exist.
     */
    @Nullable
    public static ObjMeshReader.Result parse(ResourceLocation rawId) throws java.io.IOException {
        ResourceLocation id = normalize(rawId);
        try (InputStream in = RuntimeAssets.open(id)) {
            if (in == null) {
                return null;
            }
            return ObjMeshReader.read(in, id, readSettings(id), RuntimeAssets::open);
        }
    }

    /**
     * Reads the optional sibling import settings file. For {@code foo.obj} that is
     * {@code foo.obj.json}.
     */
    private static ObjImportSettings readSettings(ResourceLocation objId) {
        ResourceLocation settingsId = HgIds.of(objId.getNamespace(),
                objId.getPath() + ".json");
        InputStream stream;
        try {
            stream = RuntimeAssets.open(settingsId);
        } catch (Exception e) {
            stream = null;
        }
        if (stream == null) {
            return ObjImportSettings.DEFAULT;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            return ObjImportSettings.fromJson(json);
        } catch (Exception e) {
            Hypergraphical.LOGGER.warn("[hypergraphical] Bad import settings {}: {}",
                    settingsId, e.toString());
            return ObjImportSettings.DEFAULT;
        }
    }

    /**
     * Uploads up to {@code budget} parsed meshes and registers their MTL-derived materials.
     * Render thread, once per frame.
     */
    public void processUploads(int budget) {
        applyBakes();
        for (int i = 0; i < budget; i++) {
            Parsed parsed = this.uploadQueue.poll();
            if (parsed == null) {
                return;
            }
            try {
                GpuMesh mesh = GpuMesh.upload(parsed.id(), parsed.result().mesh());
                GpuMesh previous = this.resident.put(parsed.id(), mesh);
                if (previous != null) {
                    previous.close();
                }
                registerAutoMaterials(parsed);
                startBake(parsed, mesh);
            } catch (Exception e) {
                Hypergraphical.LOGGER.error("[hypergraphical] Failed to upload mesh {}",
                        parsed.id(), e);
                this.failed.add(parsed.id());
            } finally {
                this.pending.remove(parsed.id());
            }
        }
    }

    private void registerAutoMaterials(Parsed parsed) {
        MtlLibrary library = parsed.result().materials();
        for (MeshData.SubMesh sub : parsed.result().mesh().subMeshes()) {
            ResourceLocation autoId = sub.autoMaterial();
            if (autoId == null) {
                continue;
            }
            MtlLibrary.Entry entry = library.get(sub.name());
            HgMaterial material = entry != null
                    ? HgMaterial.fromMtl(autoId, entry, parsed.emissiveAmbient())
                    // No matching newmtl. A neutral dielectric is a better guess than
                    // magenta here, because plenty of OBJs name groups they never define.
                    : new HgMaterial(autoId, null, null, null, null, null, false,
                            new org.joml.Vector2f(1.0F, 1.0F), new org.joml.Vector2f(),
                            0.0F, 0.5F, new org.joml.Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                            new org.joml.Vector3f(), 0.0F,
                            HgMaterial.AlphaMode.OPAQUE, 0.5F, false, 1.0F, 1.0F);
            this.materials.registerAuto(material);
        }
    }

    /**
     * Drops one mesh so the next request loads it again, e.g. after its runtime files changed
     * or were removed. Render thread.
     */
    public void evict(ResourceLocation rawId) {
        ResourceLocation id = normalize(rawId);
        evictOne(id);
        // Variants are read from this mesh's files, so they go with it
        for (Map.Entry<ResourceLocation, ResourceLocation> variant : this.variants.entrySet()) {
            if (variant.getValue().equals(id)) {
                evictOne(variant.getKey());
            }
        }
    }

    private void evictOne(ResourceLocation id) {
        cancelBake(id);
        GpuMesh mesh = this.resident.remove(id);
        if (mesh != null) {
            mesh.close();
        }
        this.failed.remove(id);
    }

    // ----------------------------------------------------------------- variants
    /**
     * Makes {@code variant} a mesh of its own, read from {@code source}'s files. It loads
     * on first request like any mesh and is matched against the occlusion rules by its own
     * location, so it can be baked differently from the source. Registering the same pair
     * again does nothing; pointing a variant at another source drops what it had loaded.
     * Evicting the source evicts its variants with it. Render thread.
     */
    public void registerVariant(ResourceLocation rawVariant, ResourceLocation rawSource) {
        ResourceLocation variant = normalize(rawVariant);
        ResourceLocation source = normalize(rawSource);
        if (variant.equals(source)) {
            return;
        }
        ResourceLocation previous = this.variants.put(variant, source);
        if (previous != null && !previous.equals(source)) {
            evictOne(variant);
        }
    }

    /** Forgets a variant and drops whatever was loaded for it. Render thread. */
    public void removeVariant(ResourceLocation rawVariant) {
        ResourceLocation variant = normalize(rawVariant);
        if (this.variants.remove(variant) != null) {
            evictOne(variant);
        }
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        // Meshes are dropped rather than reloaded eagerly: the renderer will request the ones
        // that are actually still in view on the next frame.
        clear();
    }

    public void clear() {
        for (BakeJob job : this.bakes.values()) {
            job.cancelled().set(true);
        }
        this.bakes.clear();
        this.bakeResults.clear();
        for (GpuMesh mesh : this.resident.values()) {
            mesh.close();
        }
        this.resident.clear();
        this.pending.clear();
        this.failed.clear();
        this.uploadQueue.clear();
    }

    // ------------------------------------------------------------- occlusion bake
    /**
     * Turns occlusion baking on for every mesh whose location is in {@code namespace} and
     * whose path starts with {@code pathPrefix}, or off when {@code settings} is null. When
     * several rules match a mesh the one with the longest prefix wins. Meshes already resident
     * under the prefix are dropped so they reload with the new setting. Render thread.
     */
    public void setOcclusionBaking(String namespace, String pathPrefix,
                                   @Nullable OcclusionSettings settings) {
        this.occlusionRules.removeIf(rule -> rule.namespace().equals(namespace)
                && rule.pathPrefix().equals(pathPrefix));
        if (settings != null) {
            this.occlusionRules.add(new OcclusionRule(namespace, pathPrefix, settings));
        }
        for (ResourceLocation id : List.copyOf(this.resident.keySet())) {
            if (id.getNamespace().equals(namespace) && id.getPath().startsWith(pathPrefix)) {
                evict(id);
            }
        }
    }

    /** The occlusion settings that apply to a mesh, or {@code null} when it is not baked. */
    @Nullable
    public OcclusionSettings occlusionFor(ResourceLocation rawId) {
        ResourceLocation id = normalize(rawId);
        OcclusionRule best = null;
        for (OcclusionRule rule : this.occlusionRules) {
            if (rule.namespace().equals(id.getNamespace())
                    && id.getPath().startsWith(rule.pathPrefix())
                    && (best == null || rule.pathPrefix().length() > best.pathPrefix().length())) {
                best = rule;
            }
        }
        return best == null ? null : best.settings();
    }

    /** Meshes whose occlusion bake is still running. */
    public int bakingCount() {
        return this.bakes.size();
    }

    private void startBake(Parsed parsed, GpuMesh mesh) {
        cancelBake(parsed.id());
        // Resolved at upload rather than at parse, so a rule registered while the mesh was
        // being parsed still applies to it.
        OcclusionSettings settings = occlusionFor(parsed.id());
        if (settings == null) {
            return;
        }

        BakeJob job = new BakeJob(mesh, new AtomicBoolean());
        this.bakes.put(parsed.id(), job);
        MeshData data = parsed.result().mesh();
        bakePool().execute(() -> {
            try {
                // The cache check runs here too, not on the render thread: fingerprinting a
                // large mesh is a millisecond or two that a frame should not pay for. A hit
                // lands on the next frame like any finished bake.
                int geometryHash = geometryHash(data);
                CachedBake cached;
                synchronized (this.bakeCache) {
                    cached = this.bakeCache.get(parsed.id());
                }
                if (cached != null && cached.settings().equals(settings)
                        && cached.geometryHash() == geometryHash
                        && cached.occlusion().length == data.vertexCount()) {
                    this.bakeResults.add(new BakeResult(parsed.id(), mesh, cached.occlusion()));
                    return;
                }

                long started = System.nanoTime();
                float[] occlusion = OcclusionBaker.bake(data, settings, job.cancelled()::get);
                if (occlusion == null || job.cancelled().get()) {
                    return;
                }
                synchronized (this.bakeCache) {
                    this.bakeCache.put(parsed.id(),
                            new CachedBake(settings, geometryHash, occlusion));
                }
                this.bakeResults.add(new BakeResult(parsed.id(), mesh, occlusion));
                Hypergraphical.LOGGER.debug("[hypergraphical] Baked occlusion for {}: {} verts in {} ms",
                        parsed.id(), occlusion.length, (System.nanoTime() - started) / 1_000_000L);
            } catch (Throwable e) {
                Hypergraphical.LOGGER.error("[hypergraphical] Occlusion bake failed for {}",
                        parsed.id(), e);
                this.bakes.remove(parsed.id(), job);
            }
        });
    }

    /** Patches finished bakes into their meshes, if those are still the resident ones. */
    private void applyBakes() {
        BakeResult result;
        while ((result = this.bakeResults.poll()) != null) {
            BakeJob job = this.bakes.get(result.id());
            if (job != null && job.target() == result.target()) {
                this.bakes.remove(result.id());
            }
            if (this.resident.get(result.id()) == result.target() && !result.target().closed()) {
                result.target().updateOcclusion(result.occlusion());
            }
        }
    }

    private void cancelBake(ResourceLocation id) {
        BakeJob job = this.bakes.remove(id);
        if (job != null) {
            job.cancelled().set(true);
        }
    }

    /**
     * A cheap fingerprint of a mesh's geometry, so a cached bake is only reused for the same
     * vertices and triangles. Computed on the bake pool.
     */
    private static int geometryHash(MeshData mesh) {
        return 31 * Arrays.hashCode(mesh.vertices()) + Arrays.hashCode(mesh.indices());
    }

    private static ForkJoinPool bakePool() {
        ForkJoinPool pool = bakePool;
        if (pool == null) {
            synchronized (MeshManager.class) {
                pool = bakePool;
                if (pool == null) {
                    int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
                    ClassLoader loader = MeshManager.class.getClassLoader();
                    pool = new ForkJoinPool(threads, forkJoinPool -> {
                        ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory
                                .newThread(forkJoinPool);
                        thread.setName("Hypergraphical-Occlusion-" + thread.getPoolIndex());
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MIN_PRIORITY);
                        // Mod classes come from a transforming class loader; a worker left on
                        // the system loader would fail on the first class it had to load.
                        thread.setContextClassLoader(loader);
                        return thread;
                    }, null, false);
                    bakePool = pool;
                }
            }
        }
        return pool;
    }

    public int residentCount() {
        return this.resident.size();
    }

    public int pendingCount() {
        return this.pending.size();
    }

    public int residentTriangles() {
        int total = 0;
        for (GpuMesh mesh : this.resident.values()) {
            total += mesh.triangleCount();
        }
        return total;
    }

    public void close() {
        clear();
    }
}
