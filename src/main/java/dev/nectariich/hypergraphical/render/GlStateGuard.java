package dev.nectariich.hypergraphical.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Saves the GL state our pass touches and puts it back afterwards.
 *
 * <p>This is the single most important piece of the Embeddium/Oculus compatibility story. We
 * run inside someone else's frame, between their draw calls; leaking a bound VAO, a stale
 * program or an unexpected blend function shows up as corrupted terrain or an invisible sky
 * three subsystems away, and it gets reported as "Embeddium is broken".
 *
 * <p>Restoration goes through {@link GlStateManager}/{@link RenderSystem} rather than raw GL
 * on purpose: Blaze3D caches its own copy of the GL state and skips redundant calls. Setting
 * state behind its back leaves that cache lying, and the corruption then appears only after
 * an unrelated mod happens to set the same state. Reads use plain {@code glGet} because
 * Blaze3D exposes no getters for most of this.
 */
public final class GlStateGuard {

    private int program;
    private int vertexArray;
    private int arrayBuffer;
    private int elementArrayBuffer;
    private int activeTexture;
    private boolean blend;
    private boolean cull;
    private boolean depthTest;
    private boolean depthMask;
    private int depthFunc;
    private int blendSrcRgb;
    private int blendDstRgb;
    private int blendSrcAlpha;
    private int blendDstAlpha;
    private boolean captured;

    public void capture() {
        this.program = glGetInteger(GL_CURRENT_PROGRAM);
        this.vertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        this.arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        this.elementArrayBuffer = glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);
        this.activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);

        this.blend = glIsEnabled(GL_BLEND);
        this.cull = glIsEnabled(GL_CULL_FACE);
        this.depthTest = glIsEnabled(GL_DEPTH_TEST);
        this.depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        this.depthFunc = glGetInteger(GL_DEPTH_FUNC);

        this.blendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
        this.blendDstRgb = glGetInteger(GL_BLEND_DST_RGB);
        this.blendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
        this.blendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);

        this.captured = true;
    }

    public void restore() {
        if (!this.captured) {
            return;
        }
        this.captured = false;

        GlStateManager._glBindVertexArray(this.vertexArray);
        GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, this.arrayBuffer);
        GlStateManager._glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, this.elementArrayBuffer);
        GlStateManager._glUseProgram(this.program);
        GlStateManager._activeTexture(this.activeTexture);

        if (this.blend) {
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(this.blendSrcRgb, this.blendDstRgb,
                    this.blendSrcAlpha, this.blendDstAlpha);
        } else {
            RenderSystem.disableBlend();
        }

        if (this.cull) {
            RenderSystem.enableCull();
        } else {
            RenderSystem.disableCull();
        }

        if (this.depthTest) {
            RenderSystem.enableDepthTest();
        } else {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.depthMask(this.depthMask);
        RenderSystem.depthFunc(this.depthFunc);
    }
}
