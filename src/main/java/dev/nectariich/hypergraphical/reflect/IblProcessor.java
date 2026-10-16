package dev.nectariich.hypergraphical.reflect;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.nectariich.hypergraphical.Hypergraphical;
import dev.nectariich.hypergraphical.render.FullscreenPass;
import dev.nectariich.hypergraphical.render.Gl;
import dev.nectariich.hypergraphical.render.GlStateGuard;
import dev.nectariich.hypergraphical.render.ShaderProgram;
import net.minecraft.server.packs.resources.ResourceManager;

import javax.annotation.Nullable;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Turns a raw environment capture into the two maps a split-sum PBR shader samples.
 *
 * <p>This is Karis' split-sum approximation, the same decomposition Unity and Unreal use:
 *
 * <pre>
 *   integral(L * BRDF) ~= prefilter(R, roughness) * (F0 * A + B)
 * </pre>
 *
 * <ul>
 *   <li><b>Prefiltered radiance</b> &mdash; the environment convolved with the GGX lobe, one
 *       roughness per mip level. Sampling {@code textureLod(cube, R, roughness * maxMip)} then
 *       gives a correctly blurred reflection for any roughness from one texture fetch.</li>
 *   <li><b>Irradiance</b> &mdash; the environment convolved with a cosine lobe, for the
 *       diffuse term. Tiny (16px faces): it is a very low-frequency signal.</li>
 *   <li><b>BRDF LUT</b> &mdash; the {@code (A, B)} pair as a function of {@code NdotV} and
 *       roughness. View-independent and material-independent, so one 128x128 RG16F texture is
 *       shared by every probe and baked once at startup.</li>
 * </ul>
 *
 * <p>Doing this properly rather than just mip-mapping the capture is what separates a
 * reflection that reads as a rough metal from one that reads as a blurry photo stuck to the
 * surface: a mip chain is a box filter over the cube face, which is not the GGX lobe and
 * loses the stretched highlight entirely.
 */
public final class IblProcessor implements AutoCloseable {

    public static final int IRRADIANCE_SIZE = 16;
    public static final int BRDF_LUT_SIZE = 128;

    /** Sample counts. High enough to be clean on a bake-once probe, low enough to be quick. */
    private static final int PREFILTER_SAMPLES = 64;
    private static final int IRRADIANCE_SAMPLES = 128;

    private final FullscreenPass fullscreen = new FullscreenPass();
    private final GlStateGuard guard = new GlStateGuard();
    private final int[] savedViewport = new int[4];

    @Nullable private ShaderProgram brdfShader;
    @Nullable private ShaderProgram irradianceShader;
    @Nullable private ShaderProgram prefilterShader;

    private int brdfLut;
    private int brdfFramebuffer;
    private boolean brdfBaked;

    /** Recompiles the bake shaders. Called on load and on every resource reload. */
    public void reload(ResourceManager resources) {
        closeShaders();

        this.brdfShader = ShaderProgram.compileOrNull(resources, "hg_brdf_lut",
                Hypergraphical.id("shaders/hg/fullscreen.vsh"),
                Hypergraphical.id("shaders/hg/brdf_lut.fsh"),
                Map.of());
        this.irradianceShader = ShaderProgram.compileOrNull(resources, "hg_irradiance",
                Hypergraphical.id("shaders/hg/fullscreen.vsh"),
                Hypergraphical.id("shaders/hg/irradiance.fsh"),
                Map.of("HG_SAMPLES", Integer.toString(IRRADIANCE_SAMPLES)));
        this.prefilterShader = ShaderProgram.compileOrNull(resources, "hg_prefilter",
                Hypergraphical.id("shaders/hg/fullscreen.vsh"),
                Hypergraphical.id("shaders/hg/prefilter.fsh"),
                Map.of("HG_SAMPLES", Integer.toString(PREFILTER_SAMPLES)));

        // The LUT depends only on the shader, so a reload has to re-bake it. Baking happens
        // here, eagerly, rather than lazily on first use: a reload already runs on the render
        // thread with a context and outside any pass, whereas the first use is in the middle
        // of ours, where changing the viewport and depth state would corrupt the frame.
        this.brdfBaked = false;
        bakeBrdfLut();
    }

    public boolean ready() {
        return this.brdfShader != null && this.irradianceShader != null
                && this.prefilterShader != null;
    }

    /** GL texture id of the shared BRDF LUT, or 0 if it has not been baked. */
    public int brdfLut() {
        return this.brdfLut;
    }

    private void bakeBrdfLut() {
        if (this.brdfShader == null || this.brdfBaked) {
            return;
        }
        this.brdfBaked = true;

        if (this.brdfLut == 0) {
            this.brdfLut = TextureUtil.generateTextureId();
            GlStateManager._activeTexture(GL_TEXTURE0);
            GlStateManager._bindTexture(this.brdfLut);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RG16F, BRDF_LUT_SIZE, BRDF_LUT_SIZE, 0,
                    GL_RG, GL_FLOAT, (java.nio.ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            GlStateManager._bindTexture(0);
        }
        if (this.brdfFramebuffer == 0) {
            this.brdfFramebuffer = glGenFramebuffers();
        }

        int previousFramebuffer = glGetInteger(GL_FRAMEBUFFER_BINDING);
        beginOffscreen();
        try {
            GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, this.brdfFramebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                    this.brdfLut, 0);
            GlStateManager._viewport(0, 0, BRDF_LUT_SIZE, BRDF_LUT_SIZE);

            this.brdfShader.bind();
            this.fullscreen.draw();
            ShaderProgram.unbind();
        } finally {
            GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, previousFramebuffer);
            endOffscreen();
            Gl.check("IblProcessor.bakeBrdfLut");
        }
        Hypergraphical.LOGGER.debug("[hypergraphical] Baked {}x{} BRDF LUT",
                BRDF_LUT_SIZE, BRDF_LUT_SIZE);
    }

    /**
     * Runs both convolutions for a probe whose radiance cubemap is fully captured, restoring
     * the framebuffer binding, viewport and GL state it found.
     */
    public void convolve(ReflectionProbe probe) {
        if (!ready()) {
            return;
        }
        Cubemap source = probe.radiance();
        source.generateMipmaps();

        int previousFramebuffer = glGetInteger(GL_FRAMEBUFFER_BINDING);
        beginOffscreen();
        try {
            convolveIrradiance(probe, source);
            prefilterRadiance(probe, source);
        } finally {
            GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, previousFramebuffer);
            endOffscreen();
            Gl.check("IblProcessor.convolve");
        }
    }

    /**
     * Sets up for an off-screen full-viewport pass, remembering the viewport and GL state.
     *
     * <p>Both bake paths render into small textures with their own viewport. Leaving that
     * viewport, or the disabled depth test, behind would shrink the rest of the frame into a
     * corner of the screen or stop it depth-testing &mdash; and because a bake only happens on
     * the frame a probe completes, it would look like an intermittent glitch rather than a
     * state leak.
     */
    private void beginOffscreen() {
        glGetIntegerv(GL_VIEWPORT, this.savedViewport);
        this.guard.capture();

        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
    }

    private void endOffscreen() {
        this.guard.restore();
        GlStateManager._viewport(this.savedViewport[0], this.savedViewport[1],
                this.savedViewport[2], this.savedViewport[3]);
    }

    private void convolveIrradiance(ReflectionProbe probe, Cubemap source) {
        Cubemap target = probe.irradiance();
        ShaderProgram shader = this.irradianceShader;
        if (shader == null) {
            return;
        }
        shader.bind();
        source.bindTo(0);
        shader.sampler("uEnvironment", 0);
        shader.set("uDecodeSrgb", probe.radianceIsSrgb());

        for (int face = 0; face < 6; face++) {
            target.bindFace(face, 0);
            shader.set("uFace", face);
            this.fullscreen.draw();
        }
        Cubemap.unbindFrom(0);
        ShaderProgram.unbind();

        // The top mip doubles as the map's global average, which the shading pass divides
        // by to keep probe irradiance brightness-neutral against the vanilla lightmap.
        target.generateMipmaps();
    }

    private void prefilterRadiance(ReflectionProbe probe, Cubemap source) {
        Cubemap target = probe.prefiltered();
        ShaderProgram shader = this.prefilterShader;
        if (shader == null) {
            return;
        }
        shader.bind();
        source.bindTo(0);
        shader.sampler("uEnvironment", 0);
        shader.set("uEnvironmentSize", (float) source.size());
        shader.set("uDecodeSrgb", probe.radianceIsSrgb());

        int mipLevels = target.mipLevels();
        for (int mip = 0; mip < mipLevels; mip++) {
            // Mip 0 is a mirror, the last mip is fully rough. Anything in between gets the
            // roughness that a linear textureLod on the sampling side will resolve to.
            float roughness = mipLevels <= 1 ? 0.0F : (float) mip / (float) (mipLevels - 1);
            shader.set("uRoughness", roughness);
            for (int face = 0; face < 6; face++) {
                target.bindFace(face, mip);
                shader.set("uFace", face);
                this.fullscreen.draw();
            }
        }
        Cubemap.unbindFrom(0);
        ShaderProgram.unbind();
    }

    private void closeShaders() {
        if (this.brdfShader != null) {
            this.brdfShader.close();
            this.brdfShader = null;
        }
        if (this.irradianceShader != null) {
            this.irradianceShader.close();
            this.irradianceShader = null;
        }
        if (this.prefilterShader != null) {
            this.prefilterShader.close();
            this.prefilterShader = null;
        }
    }

    @Override
    public void close() {
        closeShaders();
        this.fullscreen.close();
        if (this.brdfFramebuffer != 0) {
            glDeleteFramebuffers(this.brdfFramebuffer);
            this.brdfFramebuffer = 0;
        }
        if (this.brdfLut != 0) {
            TextureUtil.releaseTextureId(this.brdfLut);
            this.brdfLut = 0;
        }
        this.brdfBaked = false;
    }
}
