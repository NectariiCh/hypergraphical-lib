package dev.nectariich.hypergraphical.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.nectariich.hypergraphical.Hypergraphical;
import dev.nectariich.hypergraphical.mesh.obj.MtlLibrary;
import dev.nectariich.hypergraphical.util.HgIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * A PBR material: textures, their UV transform, and the scalar surface parameters.
 *
 * <p>Modelled on Unity's Standard shader rather than on glTF, because that is the mental model
 * asked for: <b>smoothness</b> (not roughness) as the authored value, tiling and offset per
 * material, and an emission map that is independent of albedo.
 *
 * <p>All texture references are plain resource paths <em>including</em> the {@code .png}
 * extension, so materials point straight at pack files and can reuse vanilla textures
 * ({@code minecraft:textures/block/iron_block.png}) without an atlas round-trip.
 *
 * <p>Colour handling: albedo and emissive are treated as sRGB and decoded to linear in the
 * shader, lighting runs in linear space, and the result is re-encoded on output. Vanilla
 * skips this entirely, but metallic/smoothness surfaces do not survive being lit in gamma
 * space &mdash; a half-rough metal comes out chalky and the falloff on a highlight is wrong.
 */
public final class HgMaterial {

    /** How the fragment's alpha is resolved. Decides which of our two passes draws it. */
    public enum AlphaMode {
        /** Alpha ignored, depth written. */
        OPAQUE,
        /** Alpha compared against a cutoff and discarded; still in the opaque pass. */
        CUTOUT,
        /** Alpha blended, depth tested but not written, drawn after translucent blocks. */
        BLEND
    }

    private final ResourceLocation id;

    /** {@code null} means "no texture": the shader gets the built-in white placeholder. */
    @Nullable public final ResourceLocation albedoMap;
    @Nullable public final ResourceLocation emissiveMap;
    @Nullable public final ResourceLocation metallicMap;
    @Nullable public final ResourceLocation roughnessMap;
    /** Parsed and carried for forward compatibility; not sampled yet. */
    @Nullable public final ResourceLocation normalMap;

    /**
     * When true, {@link #metallicMap} is a packed map with metallic in R and roughness in G
     * (the glTF convention) and {@link #roughnessMap} is ignored.
     */
    public final boolean packedMetallicRoughness;

    public final Vector2f tiling;
    public final Vector2f offset;

    public final float metallic;
    public final float smoothness;
    public final Vector4f tint;
    public final Vector3f emissiveColor;
    public final float emissiveStrength;

    public final AlphaMode alphaMode;
    public final float alphaCutoff;
    public final boolean doubleSided;

    /** Scales this material's response to reflection probes, 0 disables IBL for it. */
    public final float reflectivity;

    /** Multiplies the analytic sun highlight; 0 leaves only probe reflections. */
    public final float sunSpecular;

    /**
     * How much of a mesh's baked ambient occlusion this material shows, 0..1: multiplies the
     * instance's occlusion strength. 0 suits a surface lit from behind, like a lamp lens sunk
     * in a socket, whose corners would otherwise bake close to black. Default 1.
     */
    public final float occlusion;

    public HgMaterial(ResourceLocation id,
                      @Nullable ResourceLocation albedoMap,
                      @Nullable ResourceLocation emissiveMap,
                      @Nullable ResourceLocation metallicMap,
                      @Nullable ResourceLocation roughnessMap,
                      @Nullable ResourceLocation normalMap,
                      boolean packedMetallicRoughness,
                      Vector2f tiling,
                      Vector2f offset,
                      float metallic,
                      float smoothness,
                      Vector4f tint,
                      Vector3f emissiveColor,
                      float emissiveStrength,
                      AlphaMode alphaMode,
                      float alphaCutoff,
                      boolean doubleSided,
                      float reflectivity,
                      float sunSpecular) {
        this(id, albedoMap, emissiveMap, metallicMap, roughnessMap, normalMap, packedMetallicRoughness,
                tiling, offset, metallic, smoothness, tint, emissiveColor, emissiveStrength, alphaMode,
                alphaCutoff, doubleSided, reflectivity, sunSpecular, 1.0F);
    }

    public HgMaterial(ResourceLocation id,
                      @Nullable ResourceLocation albedoMap,
                      @Nullable ResourceLocation emissiveMap,
                      @Nullable ResourceLocation metallicMap,
                      @Nullable ResourceLocation roughnessMap,
                      @Nullable ResourceLocation normalMap,
                      boolean packedMetallicRoughness,
                      Vector2f tiling,
                      Vector2f offset,
                      float metallic,
                      float smoothness,
                      Vector4f tint,
                      Vector3f emissiveColor,
                      float emissiveStrength,
                      AlphaMode alphaMode,
                      float alphaCutoff,
                      boolean doubleSided,
                      float reflectivity,
                      float sunSpecular,
                      float occlusion) {
        this.id = id;
        this.albedoMap = albedoMap;
        this.emissiveMap = emissiveMap;
        this.metallicMap = metallicMap;
        this.roughnessMap = roughnessMap;
        this.normalMap = normalMap;
        this.packedMetallicRoughness = packedMetallicRoughness;
        this.tiling = tiling;
        this.offset = offset;
        this.metallic = metallic;
        this.smoothness = smoothness;
        this.tint = tint;
        this.emissiveColor = emissiveColor;
        this.emissiveStrength = emissiveStrength;
        this.alphaMode = alphaMode;
        this.alphaCutoff = alphaCutoff;
        this.doubleSided = doubleSided;
        this.reflectivity = reflectivity;
        this.sunSpecular = sunSpecular;
        this.occlusion = Float.isFinite(occlusion) ? clamp01(occlusion) : 1.0F;
    }

    public ResourceLocation id() {
        return this.id;
    }

    /** Whether this material draws in the blended pass. */
    public boolean blended() {
        return this.alphaMode == AlphaMode.BLEND;
    }

    public boolean hasEmissive() {
        return this.emissiveMap != null || this.emissiveStrength > 0.0F;
    }

    /** A plain white opaque material, used when a lookup fails so geometry stays visible. */
    public static HgMaterial fallback(ResourceLocation id) {
        return new HgMaterial(id, null, null, null, null, null, false,
                new Vector2f(1.0F, 1.0F), new Vector2f(),
                0.0F, 0.5F,
                new Vector4f(1.0F, 0.0F, 1.0F, 1.0F), // magenta: obviously wrong, never silent
                new Vector3f(), 0.0F,
                AlphaMode.OPAQUE, 0.5F, false, 1.0F, 1.0F);
    }

    // ------------------------------------------------------------------- loading
    public static HgMaterial fromJson(ResourceLocation id, JsonObject json) {
        ResourceLocation albedo = rl(json, "albedo", null);
        AlphaMode mode = switch (GsonHelper.getAsString(json, "alpha_mode", "opaque")
                .toLowerCase(Locale.ROOT)) {
            case "cutout", "clip", "mask" -> AlphaMode.CUTOUT;
            case "blend", "transparent" -> AlphaMode.BLEND;
            default -> AlphaMode.OPAQUE;
        };

        float smoothness;
        if (json.has("smoothness")) {
            smoothness = GsonHelper.getAsFloat(json, "smoothness");
        } else if (json.has("roughness")) {
            // Accept glTF-style authoring too; one of the two has to win.
            smoothness = 1.0F - GsonHelper.getAsFloat(json, "roughness");
        } else {
            smoothness = 0.5F;
        }

        return new HgMaterial(id,
                albedo,
                rl(json, "emissive", null),
                rl(json, "metallic_map", null),
                rl(json, "roughness_map", null),
                rl(json, "normal_map", null),
                GsonHelper.getAsBoolean(json, "packed_metallic_roughness", false),
                vec2(json, "tiling", 1.0F, 1.0F),
                vec2(json, "offset", 0.0F, 0.0F),
                clamp01(GsonHelper.getAsFloat(json, "metallic", 0.0F)),
                clamp01(smoothness),
                vec4(json, "tint", 1.0F, 1.0F, 1.0F, 1.0F),
                vec3(json, "emissive_color", 1.0F, 1.0F, 1.0F),
                Math.max(0.0F, GsonHelper.getAsFloat(json, "emissive_strength",
                        json.has("emissive") ? 1.0F : 0.0F)),
                mode,
                clamp01(GsonHelper.getAsFloat(json, "alpha_cutoff", 0.5F)),
                GsonHelper.getAsBoolean(json, "double_sided", false),
                Math.max(0.0F, GsonHelper.getAsFloat(json, "reflectivity", 1.0F)),
                Math.max(0.0F, GsonHelper.getAsFloat(json, "sun_specular", 1.0F)),
                GsonHelper.getAsFloat(json, "occlusion", 1.0F));
    }

    /**
     * Derives a material from an MTL entry, so an OBJ with its material library renders
     * correctly without any hand-written JSON. This is the "drop in an export and it works"
     * path; hand-authored JSON is for when you want control.
     */
    public static HgMaterial fromMtl(ResourceLocation id, MtlLibrary.Entry entry, boolean emissiveAmbient) {
        boolean cutout = entry.alpha >= 1.0F && entry.albedoMap != null;
        AlphaMode mode = entry.alpha < 1.0F ? AlphaMode.BLEND
                : (cutout ? AlphaMode.CUTOUT : AlphaMode.OPAQUE);

        float emissive = 0.0F;
        if (entry.emissiveMap != null) {
            emissive = 1.0F;
        } else if (entry.emissiveTint[0] + entry.emissiveTint[1] + entry.emissiveTint[2] > 0.0F) {
            emissive = emissiveAmbient ? 1.0F : 0.25F;
        }

        return new HgMaterial(id,
                entry.albedoMap,
                entry.emissiveMap,
                entry.metallicMap,
                entry.roughnessMap,
                entry.normalMap,
                false,
                new Vector2f(1.0F, 1.0F),
                new Vector2f(),
                entry.metallic,
                entry.smoothness(),
                new Vector4f(entry.albedoTint[0], entry.albedoTint[1], entry.albedoTint[2], entry.alpha),
                new Vector3f(Math.max(entry.emissiveTint[0], 1.0E-3F),
                        Math.max(entry.emissiveTint[1], 1.0E-3F),
                        Math.max(entry.emissiveTint[2], 1.0E-3F)),
                emissive,
                mode,
                0.5F,
                false,
                1.0F,
                1.0F);
    }

    /** Returns a copy with a different UV transform, for reusing one material at two scales. */
    public HgMaterial withUvTransform(Vector2f newTiling, Vector2f newOffset) {
        return new HgMaterial(this.id, this.albedoMap, this.emissiveMap, this.metallicMap,
                this.roughnessMap, this.normalMap, this.packedMetallicRoughness,
                new Vector2f(newTiling), new Vector2f(newOffset), this.metallic, this.smoothness,
                new Vector4f(this.tint), new Vector3f(this.emissiveColor), this.emissiveStrength,
                this.alphaMode, this.alphaCutoff, this.doubleSided, this.reflectivity,
                this.sunSpecular, this.occlusion);
    }

    /**
     * Returns a copy under a new id with different surface values and the same textures and UV
     * transform, for editing a material at runtime without touching the one it came from. An
     * {@code alphaMode} of {@link AlphaMode#BLEND} moves the copy to the blended pass.
     */
    public HgMaterial withSurface(ResourceLocation newId, Vector4f newTint, float newMetallic,
                                  float newSmoothness, Vector3f newEmissiveColor,
                                  float newEmissiveStrength, AlphaMode newAlphaMode,
                                  float newOcclusion) {
        return new HgMaterial(newId, this.albedoMap, this.emissiveMap, this.metallicMap,
                this.roughnessMap, this.normalMap, this.packedMetallicRoughness,
                new Vector2f(this.tiling), new Vector2f(this.offset), clamp01(newMetallic),
                clamp01(newSmoothness), new Vector4f(newTint), new Vector3f(newEmissiveColor),
                Math.max(0.0F, newEmissiveStrength), newAlphaMode, this.alphaCutoff,
                this.doubleSided, this.reflectivity, this.sunSpecular, newOcclusion);
    }

    // -------------------------------------------------------------------- helpers
    @Nullable
    private static ResourceLocation rl(JsonObject json, String key, @Nullable ResourceLocation fallback) {
        String raw = GsonHelper.getAsString(json, key, null);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        return HgIds.parse(raw);
    }

    private static Vector2f vec2(JsonObject json, String key, float dx, float dy) {
        JsonArray array = GsonHelper.getAsJsonArray(json, key, null);
        if (array == null || array.size() < 2) {
            return new Vector2f(dx, dy);
        }
        return new Vector2f(array.get(0).getAsFloat(), array.get(1).getAsFloat());
    }

    private static Vector3f vec3(JsonObject json, String key, float dx, float dy, float dz) {
        JsonArray array = GsonHelper.getAsJsonArray(json, key, null);
        if (array == null || array.size() < 3) {
            return new Vector3f(dx, dy, dz);
        }
        return new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(),
                array.get(2).getAsFloat());
    }

    private static Vector4f vec4(JsonObject json, String key, float dx, float dy, float dz, float dw) {
        JsonArray array = GsonHelper.getAsJsonArray(json, key, null);
        if (array == null || array.size() < 3) {
            return new Vector4f(dx, dy, dz, dw);
        }
        return new Vector4f(array.get(0).getAsFloat(), array.get(1).getAsFloat(),
                array.get(2).getAsFloat(),
                array.size() > 3 ? array.get(3).getAsFloat() : dw);
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }

    @Override
    public String toString() {
        return "HgMaterial[" + this.id + " metallic=" + this.metallic
                + " smoothness=" + this.smoothness + " tiling=" + this.tiling.x + "," + this.tiling.y + "]";
    }
}
