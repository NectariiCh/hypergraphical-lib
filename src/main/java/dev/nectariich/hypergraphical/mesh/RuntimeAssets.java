package dev.nectariich.hypergraphical.mesh;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Files registered from code at runtime, looked up ahead of the resource packs.
 *
 * <p>This is what lets a mesh come from somewhere other than a pack: a player's own
 * {@code .obj}, its {@code .mtl} and its textures, received over the network or read from disk.
 * Each file is registered under the {@link ResourceLocation} it would have had in a pack, so
 * {@code mtllib} and {@code map_Kd} references resolve between them exactly as they would
 * between pack files, and everything downstream (parsing, materials, textures) is unchanged.
 *
 * <p>Runtime files survive a resource reload, which drops only what was uploaded from them;
 * the next frame that needs a mesh reloads it from here.
 */
public final class RuntimeAssets {

    private static final Map<ResourceLocation, byte[]> FILES = new ConcurrentHashMap<>();

    private RuntimeAssets() {}

    public static void put(ResourceLocation location, byte[] bytes) {
        FILES.put(location, bytes);
    }

    /** Removes every file whose path starts with {@code pathPrefix}, returning what was removed. */
    public static List<ResourceLocation> removeUnder(String namespace, String pathPrefix) {
        List<ResourceLocation> removed = new ArrayList<>();
        for (ResourceLocation location : FILES.keySet()) {
            if (location.getNamespace().equals(namespace) && location.getPath().startsWith(pathPrefix)) {
                FILES.remove(location);
                removed.add(location);
            }
        }
        return removed;
    }

    public static boolean contains(ResourceLocation location) {
        return FILES.containsKey(location);
    }

    /** Whether a file exists as a runtime file or in the resource packs. */
    public static boolean exists(ResourceLocation location) {
        return FILES.containsKey(location)
                || Minecraft.getInstance().getResourceManager().getResource(location).isPresent();
    }

    /** Opens a runtime file, else the pack resource, else {@code null}. */
    @Nullable
    public static InputStream open(ResourceLocation location) throws IOException {
        byte[] bytes = FILES.get(location);
        if (bytes != null) {
            return new ByteArrayInputStream(bytes);
        }
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        return resource.isPresent() ? resource.get().open() : null;
    }
}
