package dev.nectariich.hypergraphical.material;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import dev.nectariich.hypergraphical.Hypergraphical;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL;

import static org.lwjgl.opengl.GL33C.*;

/**
 * A single GL texture owned by us rather than by {@code TextureManager}.
 *
 * <p>We manage these directly for one reason that matters and one that follows from it.
 * The one that matters: <b>wrap mode</b>. Material tiling means sampling outside [0,1], which
 * needs {@code GL_REPEAT}; vanilla's {@code SimpleTexture} clamps, because everything vanilla
 * draws lives in an atlas where repeating would bleed into a neighbour. The one that follows:
 * once we are not in the atlas we also want a real mip chain and anisotropic filtering, which
 * is what stops a tiled floor turning into shimmering noise at a grazing angle.
 */
public final class HgTexture implements AutoCloseable {

    private static final int GL_TEXTURE_MAX_ANISOTROPY = 0x84FE;
    private static final int GL_MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF;

    private static Boolean anisotropySupported;
    private static float maxAnisotropy;

    private final ResourceLocation source;
    private final int id;
    private final int width;
    private final int height;

    private HgTexture(ResourceLocation source, int id, int width, int height) {
        this.source = source;
        this.id = id;
        this.width = width;
        this.height = height;
    }

    public int id() {
        return this.id;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public ResourceLocation source() {
        return this.source;
    }

    /**
     * Uploads a decoded image as a mipmapped, repeating, anisotropically filtered texture.
     * Must run on the render thread. Consumes {@code image}.
     */
    public static HgTexture upload(ResourceLocation source, NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int id = TextureUtil.generateTextureId();

        // Mips only make sense on power-of-two dimensions; a 48x48 texture would produce a
        // chain that rounds badly and shimmers worse than no mips at all.
        int mipLevels = isPowerOfTwo(width) && isPowerOfTwo(height)
                ? 31 - Integer.numberOfLeadingZeros(Math.max(width, height))
                : 0;

        GlStateManager._activeTexture(GL_TEXTURE0);
        GlStateManager._bindTexture(id);
        TextureUtil.prepareImage(id, mipLevels, width, height);

        // blur=false, clamp=false, mipmap flag only affects the filter vanilla sets; we
        // override the filters immediately after, so it does not matter here.
        image.upload(0, 0, 0, 0, 0, width, height, false, false, mipLevels > 0, true);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
                mipLevels > 0 ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);

        if (mipLevels > 0) {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, mipLevels);
            glGenerateMipmap(GL_TEXTURE_2D);
            applyAnisotropy();
        }

        GlStateManager._bindTexture(0);
        return new HgTexture(source, id, width, height);
    }

    /** A 1x1 opaque white texture, used wherever a material leaves a slot empty. */
    public static HgTexture solid(ResourceLocation name, int argb) {
        NativeImage image = new NativeImage(1, 1, false);
        // NativeImage stores ABGR; swap the colour channels of the incoming ARGB.
        int abgr = (argb & 0xFF00FF00)
                | ((argb >> 16) & 0x000000FF)
                | ((argb << 16) & 0x00FF0000);
        image.setPixelRGBA(0, 0, abgr);
        return upload(name, image);
    }

    private static boolean isPowerOfTwo(int v) {
        return v > 0 && (v & (v - 1)) == 0;
    }

    private static void applyAnisotropy() {
        if (anisotropySupported == null) {
            anisotropySupported = GL.getCapabilities().GL_EXT_texture_filter_anisotropic;
            if (anisotropySupported) {
                maxAnisotropy = glGetFloat(GL_MAX_TEXTURE_MAX_ANISOTROPY);
                Hypergraphical.LOGGER.debug("[hypergraphical] Anisotropic filtering up to {}x",
                        maxAnisotropy);
            }
        }
        if (anisotropySupported) {
            glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY, Math.min(8.0F, maxAnisotropy));
        }
    }

    @Override
    public void close() {
        TextureUtil.releaseTextureId(this.id);
    }
}
