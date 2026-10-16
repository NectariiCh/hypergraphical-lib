package dev.nectariich.hypergraphical.reflect;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import dev.nectariich.hypergraphical.render.Gl;

import static org.lwjgl.opengl.GL33C.*;

/**
 * A GL cubemap with an optional mip chain, plus the framebuffer needed to render into
 * individual faces and mip levels.
 *
 * <p>Stored as {@code RGBA16F}. The sky is far brighter than white paper and an emissive
 * material is brighter still; in {@code RGBA8} both clip to 1.0, and then every reflection of
 * the sky comes back the same flat grey no matter how bright the sky actually is. Half floats
 * are also exactly what the GGX prefilter wants, since it averages many samples whose sum
 * routinely exceeds 1.
 *
 * <p><b>Face orientation.</b> Capture writes framebuffer row 0 into texture row 0, and GL's
 * cubemap lookup expects a specific handedness per face (see the {@code sc}/{@code tc}/
 * {@code ma} table in the GL spec). {@link ProbeCapture} accounts for that with a 180 degree
 * roll on the capture view matrix, and {@link IblProcessor}'s shaders reconstruct directions
 * with the inverse of the same table. Getting either wrong produces reflections that look
 * plausible until you walk around the object and see them slide the wrong way.
 */
public final class Cubemap implements AutoCloseable {

    private final int texture;
    private final int size;
    private final int mipLevels;
    private int framebuffer;
    private int depthRenderbuffer;
    private boolean closed;

    /**
     * @param size      per-face edge length; should be a power of two if {@code mipped}
     * @param mipped    allocate and filter a full mip chain
     * @param withDepth attach a depth renderbuffer, needed only if real geometry is rendered
     *                  directly into the faces
     */
    public Cubemap(int size, boolean mipped, boolean withDepth) {
        this.size = size;
        this.mipLevels = mipped ? 1 + (31 - Integer.numberOfLeadingZeros(size)) : 1;
        this.texture = TextureUtil.generateTextureId();

        glBindTexture(GL_TEXTURE_CUBE_MAP, this.texture);
        for (int level = 0; level < this.mipLevels; level++) {
            int levelSize = Math.max(1, size >> level);
            for (int face = 0; face < 6; face++) {
                glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, level, GL_RGBA16F,
                        levelSize, levelSize, 0, GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer) null);
            }
        }
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER,
                mipped ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAX_LEVEL, this.mipLevels - 1);
        glBindTexture(GL_TEXTURE_CUBE_MAP, 0);

        if (withDepth) {
            this.depthRenderbuffer = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, this.depthRenderbuffer);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, size, size);
            glBindRenderbuffer(GL_RENDERBUFFER, 0);
        }

        Gl.check("Cubemap alloc " + size);
    }

    public int texture() {
        return this.texture;
    }

    public int size() {
        return this.size;
    }

    public int mipLevels() {
        return this.mipLevels;
    }

    /**
     * Binds this cubemap's framebuffer with one face/mip attached as colour 0 and sets the
     * viewport. Restore the previous framebuffer yourself afterwards.
     */
    public void bindFace(int face, int mipLevel) {
        if (this.framebuffer == 0) {
            this.framebuffer = glGenFramebuffers();
        }
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, this.framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, this.texture, mipLevel);
        if (this.depthRenderbuffer != 0 && mipLevel == 0) {
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                    GL_RENDERBUFFER, this.depthRenderbuffer);
        }
        int levelSize = Math.max(1, this.size >> mipLevel);
        GlStateManager._viewport(0, 0, levelSize, levelSize);
    }

    /**
     * Copies the currently bound read framebuffer's lower-left {@code size x size} region into
     * one face of mip 0.
     */
    public void copyFromReadBuffer(int face) {
        glBindTexture(GL_TEXTURE_CUBE_MAP, this.texture);
        glCopyTexSubImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, 0, 0, 0, 0,
                this.size, this.size);
        glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
        Gl.check("Cubemap.copyFromReadBuffer");
    }

    public void generateMipmaps() {
        if (this.mipLevels <= 1) {
            return;
        }
        glBindTexture(GL_TEXTURE_CUBE_MAP, this.texture);
        glGenerateMipmap(GL_TEXTURE_CUBE_MAP);
        glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
    }

    /** Binds to a texture unit. Cubemap bindings are not tracked by Blaze3D, so raw GL is safe. */
    public void bindTo(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_CUBE_MAP, this.texture);
    }

    public static void unbindFrom(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
    }

    /** Approximate VRAM footprint in bytes, mip chain included. */
    public long byteSize() {
        long total = 0L;
        for (int level = 0; level < this.mipLevels; level++) {
            long levelSize = Math.max(1, this.size >> level);
            total += levelSize * levelSize * 6L * 8L; // RGBA16F
        }
        return total;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.framebuffer != 0) {
            glDeleteFramebuffers(this.framebuffer);
            this.framebuffer = 0;
        }
        if (this.depthRenderbuffer != 0) {
            glDeleteRenderbuffers(this.depthRenderbuffer);
            this.depthRenderbuffer = 0;
        }
        TextureUtil.releaseTextureId(this.texture);
    }
}
