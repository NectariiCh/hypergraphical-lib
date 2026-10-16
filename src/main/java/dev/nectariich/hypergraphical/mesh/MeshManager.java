package dev.nectariich.hypergraphical.mesh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nectariich.hypergraphical.Hypergraphical;
import dev.nectariich.hypergraphical.material.HgMaterial;
import dev.nectariich.hypergraphical.material.MaterialManager;
import dev.nectariich.hypergraphical.mesh.obj.MtlLibrary;
import dev.nectariich.hypergraphical.mesh.obj.ObjImportSettings;
import dev.nectariich.hypergraphical.mesh.obj.ObjMeshReader;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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
 */
public final class MeshManager implements ResourceManagerReloadListener {

    private final MaterialManager materials;

    private final Map<ResourceLocation, GpuMesh> resident = new ConcurrentHashMap<>();
    private final Set<ResourceLocation> pending = ConcurrentHashMap.newKeySet();
    private final Set<ResourceLocation> failed = ConcurrentHashMap.newKeySet();
    private final Queue<Parsed> uploadQueue = new ConcurrentLinkedQueue<>();

    private record Parsed(ResourceLocation id, ObjMeshReader.Result result, boolean emissiveAmbient) {}

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

    /** Whether a mesh resource exists, without triggering a load. */
    public static boolean exists(ResourceLocation rawId) {
        return Minecraft.getInstance().getResourceManager()
                .getResource(normalize(rawId)).isPresent();
    }

    public static ResourceLocation normalize(ResourceLocation id) {
        return id.getPath().endsWith(".obj") ? id
                : new ResourceLocation(id.getNamespace(), id.getPath() + ".obj");
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
        Util.backgroundExecutor().execute(() -> {
            try {
                ResourceManager resources = Minecraft.getInstance().getResourceManager();

                Optional<Resource> objResource = resources.getResource(id);
                if (objResource.isEmpty()) {
                    Hypergraphical.LOGGER.warn("[hypergraphical] Mesh not found: {}", id);
                    this.failed.add(id);
                    this.pending.remove(id);
                    return;
                }

                ObjImportSettings settings = readSettings(resources, id);

                ObjMeshReader.Result result;
                try (InputStream in = objResource.get().open()) {
                    result = ObjMeshReader.read(in, id, settings,
                            mtl -> resources.getResource(mtl)
                                    .map(resource -> {
                                        try {
                                            return resource.open();
                                        } catch (Exception e) {
                                            return null;
                                        }
                                    })
                                    .orElse(null));
                }

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
     * Reads the optional sibling import settings file. For {@code foo.obj} that is
     * {@code foo.obj.json}.
     */
    private static ObjImportSettings readSettings(ResourceManager resources, ResourceLocation objId) {
        ResourceLocation settingsId = new ResourceLocation(objId.getNamespace(),
                objId.getPath() + ".json");
        Optional<Resource> resource = resources.getResource(settingsId);
        if (resource.isEmpty()) {
            return ObjImportSettings.DEFAULT;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8))) {
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

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        // Meshes are dropped rather than reloaded eagerly: the renderer will request the ones
        // that are actually still in view on the next frame.
        clear();
    }

    public void clear() {
        for (GpuMesh mesh : this.resident.values()) {
            mesh.close();
        }
        this.resident.clear();
        this.pending.clear();
        this.failed.clear();
        this.uploadQueue.clear();
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
