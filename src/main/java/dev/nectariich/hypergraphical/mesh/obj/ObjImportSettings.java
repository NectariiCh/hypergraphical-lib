package dev.nectariich.hypergraphical.mesh.obj;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

import javax.annotation.Nullable;

/**
 * Import options for one {@code .obj}. Read from an optional sibling file: for
 * {@code models/obj/lantern.obj}, the settings live at {@code models/obj/lantern.obj.json}.
 * Absent file means defaults, so a bare {@code .obj} dropped into a resource pack just works.
 *
 * <p>The option names mirror Forge's {@code forge:obj} model loader
 * ({@code flip_v}, {@code mtl_override}, {@code emissive_ambient}, {@code automatic_culling})
 * so that the same asset behaves the same whether it is drawn through the vanilla block
 * pipeline via {@code "loader": "forge:obj"} or through our PBR pass.
 *
 * @param flipV            flip the V texture coordinate. Default {@code true}: OBJ places
 *                         v=0 at the bottom of the image, our textures upload row 0 first,
 *                         so an unflipped OBJ appears upside down.
 * @param normals          how to handle missing or unwanted {@code vn} data
 * @param scale            uniform scale applied at import, for exports in the wrong unit
 * @param mtlOverride      use this {@code .mtl} instead of the one named by {@code mtllib}
 * @param autoMaterials    derive materials from the {@code .mtl} so an OBJ renders with no
 *                         hand-written material JSON at all
 * @param emissiveAmbient  treat MTL {@code Ke} as fullbright emission rather than a tint
 * @param doubleSided      disable backface culling for the whole mesh
 */
public record ObjImportSettings(boolean flipV,
                                NormalMode normals,
                                float scale,
                                @Nullable ResourceLocation mtlOverride,
                                boolean autoMaterials,
                                boolean emissiveAmbient,
                                boolean doubleSided) {

    public enum NormalMode {
        /** Use {@code vn} if present, otherwise generate smooth normals. */
        AUTO,
        /** Ignore {@code vn}; average face normals per position. */
        SMOOTH,
        /** Ignore {@code vn}; every triangle gets its geometric normal. */
        FLAT
    }

    public static final ObjImportSettings DEFAULT =
            new ObjImportSettings(true, NormalMode.AUTO, 1.0F, null, true, false, false);

    public static ObjImportSettings fromJson(JsonObject json) {
        String mtl = GsonHelper.getAsString(json, "mtl_override", null);
        return new ObjImportSettings(
                GsonHelper.getAsBoolean(json, "flip_v", DEFAULT.flipV()),
                parseNormals(GsonHelper.getAsString(json, "normals", "auto")),
                GsonHelper.getAsFloat(json, "scale", DEFAULT.scale()),
                mtl == null || mtl.isEmpty() ? null : new ResourceLocation(mtl),
                GsonHelper.getAsBoolean(json, "auto_materials", DEFAULT.autoMaterials()),
                GsonHelper.getAsBoolean(json, "emissive_ambient", DEFAULT.emissiveAmbient()),
                GsonHelper.getAsBoolean(json, "double_sided", DEFAULT.doubleSided()));
    }

    private static NormalMode parseNormals(String raw) {
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "smooth" -> NormalMode.SMOOTH;
            case "flat" -> NormalMode.FLAT;
            default -> NormalMode.AUTO;
        };
    }
}
