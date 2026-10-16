package dev.nectariich.hypergraphical.material;

import com.mojang.blaze3d.platform.NativeImage;
import dev.nectariich.hypergraphical.Hypergraphical;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Texture residency for material maps.
 *
 * <p>Loading is split: PNG decode happens on a background thread, the GL upload happens on the
 * render thread from a queue drained a few textures at a time. Decoding a 2K albedo inline
 * during the render pass is a visible stall, and a material can easily pull four maps at
 * once, so a first-frame material change would hitch every time without this.
 *
 * <p>Until a texture arrives, callers get the white or black placeholder appropriate to the
 * slot. That is why a newly placed model appears untextured for a frame or two rather than
 * disappearing: an unlit hole is a much worse failure mode than a late texture.
 */
public final class TextureBank {

    private final Map<ResourceLocation, HgTexture> resident = new ConcurrentHashMap<>();
    private final Set<ResourceLocation> pending = ConcurrentHashMap.newKeySet();
    private final Set<ResourceLocation> failed = ConcurrentHashMap.newKeySet();
    private final Queue<Decoded> uploadQueue = new ConcurrentLinkedQueue<>();

    @Nullable
    private HgTexture white;
    @Nullable
    private HgTexture black;
    @Nullable
    private HgTexture flatNormal;

    private record Decoded(ResourceLocation location, NativeImage image) {}

    /** Uploads the built-in placeholders. Render thread, once per resource reload. */
    public void createPlaceholders() {
        closePlaceholders();
        this.white = HgTexture.solid(Hypergraphical.id("builtin/white"), 0xFFFFFFFF);
        this.black = HgTexture.solid(Hypergraphical.id("builtin/black"), 0xFF000000);
        // 0.5,0.5,1.0 is a flat tangent-space normal; unused today, ready for normal mapping.
        this.flatNormal = HgTexture.solid(Hypergraphical.id("builtin/flat_normal"), 0xFF8080FF);
    }

    public int white() {
        return this.white != null ? this.white.id() : 0;
    }

    public int black() {
        return this.black != null ? this.black.id() : 0;
    }

    public int flatNormal() {
        return this.flatNormal != null ? this.flatNormal.id() : 0;
    }

    /**
     * GL id for a texture, queueing a background load on first request.
     *
     * @param fallback id returned while the texture is loading, or if it failed to load
     */
    public int get(@Nullable ResourceLocation location, int fallback) {
        if (location == null) {
            return fallback;
        }
        HgTexture texture = this.resident.get(location);
        if (texture != null) {
            return texture.id();
        }
        if (this.failed.contains(location)) {
            return fallback;
        }
        requestLoad(location);
        return fallback;
    }

    private void requestLoad(ResourceLocation location) {
        if (!this.pending.add(location)) {
            return;
        }
        Util.backgroundExecutor().execute(() -> {
            try {
                Optional<Resource> resource = Minecraft.getInstance().getResourceManager()
                        .getResource(location);
                if (resource.isEmpty()) {
                    Hypergraphical.LOGGER.warn("[hypergraphical] Material texture not found: {}",
                            location);
                    this.failed.add(location);
                    this.pending.remove(location);
                    return;
                }
                NativeImage image;
                try (InputStream in = resource.get().open()) {
                    image = NativeImage.read(in);
                }
                this.uploadQueue.add(new Decoded(location, image));
            } catch (Exception e) {
                Hypergraphical.LOGGER.warn("[hypergraphical] Failed to decode {}: {}",
                        location, e.toString());
                this.failed.add(location);
                this.pending.remove(location);
            }
        });
    }

    /**
     * Uploads up to {@code budget} decoded textures. Render thread, once per frame. The budget
     * exists so that walking into an area with fifty new materials spreads the uploads over
     * several frames instead of dropping one.
     */
    public void processUploads(int budget) {
        for (int i = 0; i < budget; i++) {
            Decoded decoded = this.uploadQueue.poll();
            if (decoded == null) {
                return;
            }
            try {
                HgTexture texture = HgTexture.upload(decoded.location(), decoded.image());
                HgTexture previous = this.resident.put(decoded.location(), texture);
                if (previous != null) {
                    previous.close();
                }
            } catch (Exception e) {
                Hypergraphical.LOGGER.error("[hypergraphical] Failed to upload {}: {}",
                        decoded.location(), e.toString());
                this.failed.add(decoded.location());
            } finally {
                this.pending.remove(decoded.location());
            }
        }
    }

    public boolean isResident(ResourceLocation location) {
        return this.resident.containsKey(location);
    }

    public int residentCount() {
        return this.resident.size();
    }

    public int pendingCount() {
        return this.pending.size();
    }

    /** Frees every texture. Render thread. */
    public void clear() {
        for (HgTexture texture : this.resident.values()) {
            texture.close();
        }
        this.resident.clear();
        this.pending.clear();
        this.failed.clear();
        Decoded decoded;
        while ((decoded = this.uploadQueue.poll()) != null) {
            decoded.image().close();
        }
    }

    private void closePlaceholders() {
        if (this.white != null) {
            this.white.close();
            this.white = null;
        }
        if (this.black != null) {
            this.black.close();
            this.black = null;
        }
        if (this.flatNormal != null) {
            this.flatNormal.close();
            this.flatNormal = null;
        }
    }

    public void close() {
        clear();
        closePlaceholders();
    }
}
