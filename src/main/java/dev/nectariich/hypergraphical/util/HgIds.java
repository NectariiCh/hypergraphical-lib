package dev.nectariich.hypergraphical.util;

import net.minecraft.resources.ResourceLocation;

/**
 * {@link ResourceLocation} factories that compile on both Minecraft versions.
 *
 * <p>1.21.1 made the constructors private and added {@code fromNamespaceAndPath} and
 * {@code parse}, neither of which exists on 1.20.1. Both forms throw on an invalid id, so
 * callers see the same behaviour on either version. Internal: not part of the API.
 */
public final class HgIds {

    private HgIds() {}

    /** {@code namespace:path}. */
    public static ResourceLocation of(String namespace, String path) {
        //? if forge {
        return new ResourceLocation(namespace, path);
        //?} else {
        /*return ResourceLocation.fromNamespaceAndPath(namespace, path);
        *///?}
    }

    /** Parses {@code namespace:path}, defaulting the namespace to {@code minecraft}. */
    public static ResourceLocation parse(String id) {
        //? if forge {
        return new ResourceLocation(id);
        //?} else {
        /*return ResourceLocation.parse(id);
        *///?}
    }
}
