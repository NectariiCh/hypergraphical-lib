package dev.nectariich.hypergraphical.render;

import com.mojang.blaze3d.platform.GlStateManager;

import static org.lwjgl.opengl.GL33C.*;

/**
 * A single empty VAO used to launch full-viewport shader passes.
 *
 * <p>The geometry is generated in the vertex shader from {@code gl_VertexID}, so there is no
 * vertex buffer at all &mdash; three vertices covering the whole viewport. This is how the IBL
 * bake passes (BRDF LUT, irradiance convolution, GGX prefilter) and the procedural
 * environment capture rasterise, and a triangle beats a quad because it avoids the
 * diagonal-edge quad-helper waste along the seam.
 */
public final class FullscreenPass implements AutoCloseable {

    private int vao;

    public void draw() {
        if (this.vao == 0) {
            this.vao = GlStateManager._glGenVertexArrays();
        }
        GlStateManager._glBindVertexArray(this.vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    @Override
    public void close() {
        if (this.vao != 0) {
            GlStateManager._glDeleteVertexArrays(this.vao);
            this.vao = 0;
        }
    }
}
