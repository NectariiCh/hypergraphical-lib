package dev.nectariich.hypergraphical.material;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nectariich.hypergraphical.Hypergraphical;
import dev.nectariich.hypergraphical.util.HgIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and resolves {@link HgMaterial}s.
 *
 * <p>Materials come from three places, checked in this order:
 * <ol>
 *   <li><b>Pack JSON</b> at {@code assets/&lt;ns&gt;/hg_materials/&lt;name&gt;.json}, resolving to
 *       the id {@code ns:name}. Reloaded on F3+T like any other resource.</li>
 *   <li><b>Runtime registrations</b> from code, via {@link #register}. Survive resource
 *       reloads, because the mod that registered them is not reloading.</li>
 *   <li><b>Auto-materials</b> derived from an OBJ's {@code .mtl}, registered by the mesh
 *       manager when a mesh loads.</li>
 * </ol>
 *
 * <p>Pack JSON wins over an auto-material with the same id, which is what lets you drop in an
 * export, see it render immediately, and then override just the one material you want to
 * tune without editing the {@code .mtl}.
 */
public final class MaterialManager implements ResourceManagerReloadListener {

    private static final String DIRECTORY = "hg_materials";
    private static final String SUFFIX = ".json";

    private final Map<ResourceLocation, HgMaterial> packMaterials = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, HgMaterial> runtimeMaterials = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, HgMaterial> autoMaterials = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, HgMaterial> fallbacks = new ConcurrentHashMap<>();

    private final TextureBank textures = new TextureBank();

    public TextureBank textures() {
        return this.textures;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        this.packMaterials.clear();
        this.autoMaterials.clear();
        this.fallbacks.clear();
        this.textures.clear();

        Map<ResourceLocation, Resource> found = resourceManager.listResources(DIRECTORY,
                location -> location.getPath().endsWith(SUFFIX));

        int loaded = 0;
        for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
            ResourceLocation file = entry.getKey();
            String path = file.getPath();
            String name = path.substring(DIRECTORY.length() + 1, path.length() - SUFFIX.length());
            ResourceLocation id = HgIds.of(file.getNamespace(), name);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8))) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                this.packMaterials.put(id, HgMaterial.fromJson(id, json));
                loaded++;
            } catch (Exception e) {
                Hypergraphical.LOGGER.error("[hypergraphical] Failed to load material {}: {}",
                        file, e.toString());
            }
        }

        Hypergraphical.LOGGER.info("[hypergraphical] Loaded {} material(s)", loaded);
    }

    /**
     * Registers a material from code. Overwrites any previous registration with the same id
     * and survives resource reloads.
     */
    public void register(HgMaterial material) {
        this.runtimeMaterials.put(material.id(), material);
    }

    /** Registers a material derived from an OBJ's MTL. Cleared on resource reload. */
    public void registerAuto(HgMaterial material) {
        this.autoMaterials.putIfAbsent(material.id(), material);
    }

    /**
     * Resolves a material id, preferring pack JSON, then runtime, then MTL-derived.
     *
     * @param requested the material the instance asked for; may be {@code null}
     * @param autoFallback the sub-mesh's MTL-derived id, used when {@code requested} is
     *                     absent or unknown
     */
    public HgMaterial resolve(@Nullable ResourceLocation requested,
                              @Nullable ResourceLocation autoFallback) {
        HgMaterial material = requested == null ? null : lookup(requested);
        if (material != null) {
            return material;
        }
        if (autoFallback != null) {
            material = lookup(autoFallback);
            if (material != null) {
                return material;
            }
        }
        ResourceLocation missing = requested != null ? requested
                : (autoFallback != null ? autoFallback : Hypergraphical.id("missing"));
        return this.fallbacks.computeIfAbsent(missing, id -> {
            Hypergraphical.LOGGER.warn("[hypergraphical] Unknown material {} - using fallback", id);
            return HgMaterial.fallback(id);
        });
    }

    @Nullable
    private HgMaterial lookup(ResourceLocation id) {
        HgMaterial material = this.packMaterials.get(id);
        if (material != null) {
            return material;
        }
        material = this.runtimeMaterials.get(id);
        if (material != null) {
            return material;
        }
        return this.autoMaterials.get(id);
    }

    /** Every material id that would resolve, for command suggestions and diagnostics. */
    public java.util.Set<ResourceLocation> ids() {
        java.util.Set<ResourceLocation> all = new java.util.TreeSet<>(
                java.util.Comparator.comparing(ResourceLocation::toString));
        all.addAll(this.packMaterials.keySet());
        all.addAll(this.runtimeMaterials.keySet());
        all.addAll(this.autoMaterials.keySet());
        return all;
    }

    public boolean has(ResourceLocation id) {
        return lookup(id) != null;
    }

    public int count() {
        return this.packMaterials.size() + this.runtimeMaterials.size() + this.autoMaterials.size();
    }

    public void close() {
        this.textures.close();
        this.packMaterials.clear();
        this.autoMaterials.clear();
        this.fallbacks.clear();
    }
}
