package dev.nectariich.hypergraphical.reflect;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import dev.nectariich.hypergraphical.HgConfig;
import dev.nectariich.hypergraphical.Hypergraphical;
import dev.nectariich.hypergraphical.compat.OculusCompat;
import dev.nectariich.hypergraphical.render.FullscreenPass;
import dev.nectariich.hypergraphical.render.Gl;
import dev.nectariich.hypergraphical.render.GlStateGuard;
import dev.nectariich.hypergraphical.render.ShaderProgram;
import net.minecraft.Util;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Fills a probe's radiance cubemap, one face at a time.
 *
 * <h2>LEVEL strategy &mdash; a real capture of the world</h2>
 *
 * <p>Calls {@code LevelRenderer#renderLevel} again, from the probe's position, with a 90 degree
 * frustum, and copies the result into a cubemap face. Terrain, entities, block entities, the
 * sky and our own instances all appear, because they are all drawn by that method. This is
 * what makes the reflections real rather than screen-space: nothing has to be on screen to be
 * reflected, so a mirror can show what is behind the camera or around a corner, which is
 * exactly where SSR falls apart.
 *
 * <p>Three implementation details carry the whole thing:
 *
 * <ol>
 *   <li><b>Rendering into the main framebuffer, not our own.</b> {@code renderLevel} rebinds
 *       {@code Minecraft#getMainRenderTarget()} in four places, so an FBO of ours would be
 *       silently dropped partway through. Instead we render into the bottom-left
 *       {@code res x res} corner of the real framebuffer and copy out with
 *       {@code glCopyTexSubImage2D}. Every one of those rebinds passes
 *       {@code setViewport = false}, so our small viewport survives them. Capture happens at
 *       the very start of a frame, so the garbage left in the corner is overwritten before
 *       anything reaches the screen.</li>
 *   <li><b>A 180 degree roll on the view matrix.</b> Minecraft's camera basis and GL's
 *       per-face cubemap basis differ by exactly that, on every face. Without it reflections
 *       look superficially right and are point-mirrored.</li>
 *   <li><b>The far plane, not the render distance option.</b> Limiting capture depth by
 *       writing to {@code options.renderDistance()} would trigger a full chunk graph rebuild
 *       per face. A tighter far plane culls the same chunks through the frustum, for free.</li>
 * </ol>
 *
 * <h2>ENVIRONMENT strategy &mdash; procedural sky</h2>
 *
 * <p>A shader-generated sky/sun/ground cubemap. Always safe, costs almost nothing, and is the
 * automatic fallback when a real capture is not possible: an active Oculus shader pack (Iris
 * is not re-entrant), Fabulous graphics (its post chain composites at window size and would
 * stomp our viewport), or a capture that threw.
 */
public final class ProbeCapture implements AutoCloseable {

    /**
     * Minecraft yaw/pitch per cubemap face, derived from the {@code sc}/{@code tc}/{@code ma}
     * table in the GL spec. Order matches {@code GL_TEXTURE_CUBE_MAP_POSITIVE_X + face}.
     */
    private static final float[][] FACE_ANGLES = {
            {-90.0F, 0.0F},   // +X  east
            {90.0F, 0.0F},    // -X  west
            {0.0F, -90.0F},   // +Y  up
            {0.0F, 90.0F},    // -Y  down
            {0.0F, 0.0F},     // +Z  south
            {180.0F, 0.0F},   // -Z  north
    };

    /**
     * Floor on the capture far plane, in blocks.
     *
     * <p>Minecraft draws the sun and moon as quads 100 blocks from the camera (corner distance
     * ~110 with their 30-block half-size) and the sky and void discs at 512. A far plane below
     * that clips the celestial bodies out of the frustum entirely, so every reflection loses
     * the single brightest thing in the sky and the horizon band goes flat. The chunk saving
     * from a tighter plane is not worth a sunless sky, so the configured distance only takes
     * effect above this.
     */
    private static final float MIN_CAPTURE_FAR = 144.0F;

    private final ProbeCamera camera = new ProbeCamera();
    private final int[] savedViewport = new int[4];
    private final GlStateGuard guard = new GlStateGuard();
    private final FullscreenPass fullscreen = new FullscreenPass();

    @Nullable
    private ShaderProgram environmentShader;

    /** True while a capture is in flight, so nothing re-enters and instances can opt out. */
    private boolean capturing;

    private boolean warnedFabulous;
    private boolean warnedShaders;
    private boolean warnedViewport;

    public void reload(ResourceManager resources) {
        if (this.environmentShader != null) {
            this.environmentShader.close();
        }
        this.environmentShader = ShaderProgram.compileOrNull(resources, "hg_environment",
                Hypergraphical.id("shaders/hg/fullscreen.vsh"),
                Hypergraphical.id("shaders/hg/environment.fsh"),
                Map.of());
    }

    public boolean capturing() {
        return this.capturing;
    }

    /**
     * Which strategy is actually usable right now, downgrading the configured one when the
     * environment does not allow a real capture.
     */
    public HgConfig.ProbeCapture effectiveStrategy() {
        HgConfig.ProbeCapture configured = HgConfig.PROBE_CAPTURE.get();
        if (configured != HgConfig.ProbeCapture.LEVEL) {
            return configured;
        }

        if (HgConfig.OCULUS_DEFER.get() && OculusCompat.shaderPackInUse()) {
            if (!this.warnedShaders) {
                this.warnedShaders = true;
                Hypergraphical.LOGGER.info("[hypergraphical] Oculus shader pack active - "
                        + "reflection probes fall back to procedural environment capture.");
            }
            return HgConfig.ProbeCapture.ENVIRONMENT;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.graphicsMode().get() == GraphicsStatus.FABULOUS) {
            if (!this.warnedFabulous) {
                this.warnedFabulous = true;
                Hypergraphical.LOGGER.info("[hypergraphical] Fabulous graphics composites at "
                        + "window resolution, which is incompatible with off-screen probe "
                        + "capture - falling back to procedural environment capture. "
                        + "Switch to Fancy for real reflections.");
            }
            return HgConfig.ProbeCapture.ENVIRONMENT;
        }
        return HgConfig.ProbeCapture.LEVEL;
    }

    /**
     * Captures one face. Must be called outside {@code renderLevel} &mdash; the renderer's own
     * pass runs inside it, and re-entering from there would recurse.
     *
     * @return true if the face was captured
     */
    public boolean captureFace(ReflectionProbe probe, int face, float partialTick) {
        if (this.capturing) {
            return false;
        }
        HgConfig.ProbeCapture strategy = effectiveStrategy();
        if (strategy == HgConfig.ProbeCapture.OFF) {
            return false;
        }
        if (strategy == HgConfig.ProbeCapture.LEVEL && !probe.levelCaptureFailed()) {
            try {
                return captureLevelFace(probe, face, partialTick);
            } catch (Throwable t) {
                // A failed re-entrant render must never take the frame down with it. Mark the
                // probe so we stop retrying and quietly drop to the procedural sky.
                probe.markLevelCaptureFailed();
                Hypergraphical.LOGGER.error("[hypergraphical] Level capture failed for probe {} - "
                        + "falling back to environment capture for it", probe.id(), t);
            } finally {
                this.capturing = false;
            }
        }
        return captureEnvironmentFace(probe, face, partialTick);
    }

    // ------------------------------------------------------------- level capture
    private boolean captureLevelFace(ReflectionProbe probe, int face, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return false;
        }

        RenderTarget mainTarget = minecraft.getMainRenderTarget();

        Cubemap radiance = probe.radiance();
        int resolution = radiance.size();

        // We read the capture back out of the main framebuffer, so it has to fit inside it.
        // Resizing the probe here would invalidate it mid-bake and restart the face counter,
        // so the resize belongs to ProbeManager, between probes. Bail out and let it happen.
        if (resolution < 16 || resolution > Math.min(mainTarget.width, mainTarget.height)) {
            return false;
        }

        this.capturing = true;
        glGetIntegerv(GL_VIEWPORT, this.savedViewport);
        this.guard.capture();
        RenderSystem.backupProjectionMatrix();

        int previousFramebuffer = glGetInteger(GL_FRAMEBUFFER_BINDING);
        PoseStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushPose();

        try {
            float yaw = FACE_ANGLES[face][0];
            float pitch = FACE_ANGLES[face][1];

            this.camera.place(level, minecraft.player, probe.center(), yaw, pitch);

            float far = Math.max(HgConfig.PROBE_CAPTURE_DISTANCE.get() * 16.0F, MIN_CAPTURE_FAR);
            Matrix4f projection = new Matrix4f().perspective(
                    (float) Math.toRadians(90.0D), 1.0F, 0.05F, far);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);

            PoseStack pose = new PoseStack();
            // See the class docs: Minecraft's camera basis and GL's cubemap face basis differ
            // by a 180 degree roll. Applying it here means the copy into the face needs no
            // flip, and the sampling shaders need no per-face fixups.
            pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
            pose.mulPose(Axis.XP.rotationDegrees(pitch));
            pose.mulPose(Axis.YP.rotationDegrees(yaw + 180.0F));

            mainTarget.bindWrite(false);
            GlStateManager._viewport(0, 0, resolution, resolution);

            glEnable(GL_SCISSOR_TEST);
            glScissor(0, 0, resolution, resolution);
            RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 1.0F);
            RenderSystem.clear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            glDisable(GL_SCISSOR_TEST);

            // A tight upload deadline stops chunk meshing from piling onto a capture frame.
            long deadline = Util.getNanos() + 500_000L;
            minecraft.levelRenderer.renderLevel(pose, partialTick, deadline, false,
                    this.camera, minecraft.gameRenderer, minecraft.gameRenderer.lightTexture(),
                    projection);

            // The whole strategy rests on our small viewport surviving renderLevel, which it
            // does only because every bindWrite in there passes setViewport = false. If a mod
            // in the chain resets it, the level is drawn full-screen and the copy below lifts
            // a postage stamp out of the bottom-left corner instead - six near-identical
            // crops of whatever happens to be down there, which reads as a dim, wrong,
            // strangely uniform reflection rather than as an outright failure. Cheap to check,
            // and it is the first thing worth knowing when a reflection looks wrong.
            checkViewportSurvived(resolution);

            mainTarget.bindWrite(false);
            radiance.copyFromReadBuffer(face);
            probe.setRadianceIsSrgb(true);
            return true;
        } finally {
            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();

            GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, previousFramebuffer);
            restoreViewport();
            this.guard.restore();

            // The chunk visibility graph now reflects the probe camera. Force the main view
            // to rebuild its own, or terrain pops for a frame.
            minecraft.levelRenderer.needsUpdate();
            this.capturing = false;
            Gl.check("ProbeCapture.captureLevelFace");
        }
    }

    /**
     * Largest per-face resolution a LEVEL capture can currently produce: a power of two that
     * fits both the main framebuffer's shorter side and the driver's cubemap limit.
     *
     * <p>Bounded by the framebuffer because the capture is read back out of it, which also
     * means it moves when the window is resized.
     */
    public int maxCaptureResolution() {
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        int limit = Math.min(Math.min(target.width, target.height), Gl.maxCubemapSize());
        return HgConfig.floorPow2(Math.max(0, limit));
    }

    // ------------------------------------------------------- environment capture
    private boolean captureEnvironmentFace(ReflectionProbe probe, int face, float partialTick) {
        ShaderProgram shader = this.environmentShader;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (shader == null || level == null) {
            return false;
        }

        Cubemap radiance = probe.radiance();
        this.capturing = true;
        glGetIntegerv(GL_VIEWPORT, this.savedViewport);
        this.guard.capture();
        int previousFramebuffer = glGetInteger(GL_FRAMEBUFFER_BINDING);

        try {
            Vec3 sky = level.getSkyColor(probe.center(), partialTick);
            float[] fog = RenderSystem.getShaderFogColor();

            // Same construction LevelRenderer#renderSky uses to place the sun disc.
            float timeOfDay = level.getTimeOfDay(partialTick);
            double angle = timeOfDay * (Math.PI * 2.0D);
            float sunX = (float) -Math.sin(angle);
            float sunY = (float) Math.cos(angle);

            // Daylight strength: 1 at noon, 0 below the horizon, with a soft twilight ramp.
            // getSkyDarken is a daylight factor (1 at noon, 0 at night), not a darkness one.
            float elevation = Math.max(0.0F, Math.min(1.0F, sunY * 2.0F + 0.35F));
            float daylight = elevation * level.getSkyDarken(partialTick);

            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);

            shader.bind();
            shader.set("uFace", face);
            shader.set("uSkyColor", (float) sky.x, (float) sky.y, (float) sky.z);
            shader.set("uFogColor", fog[0], fog[1], fog[2]);
            shader.set("uSunDirection", sunX, sunY, 0.0F);
            // A floor keeps a night sky from baking to pure black, so a metal still reads.
            shader.set("uSunIntensity", Math.max(0.12F, daylight));
            // A dim ground bounce keeps the underside of a metal from reading as a black hole.
            shader.set("uGroundColor",
                    (float) sky.x * 0.25F + 0.02F,
                    (float) sky.y * 0.22F + 0.02F,
                    (float) sky.z * 0.20F + 0.02F);

            radiance.bindFace(face, 0);
            this.fullscreen.draw();
            ShaderProgram.unbind();
            probe.setRadianceIsSrgb(false);
            return true;
        } finally {
            GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, previousFramebuffer);
            restoreViewport();
            this.guard.restore();
            this.capturing = false;
            Gl.check("ProbeCapture.captureEnvironmentFace");
        }
    }

    /**
     * Warns once if {@code renderLevel} left the viewport somewhere other than the corner we
     * set it to. See the call site for why that matters.
     */
    private void checkViewportSurvived(int resolution) {
        if (this.warnedViewport) {
            return;
        }
        int[] now = new int[4];
        glGetIntegerv(GL_VIEWPORT, now);
        if (now[0] == 0 && now[1] == 0 && now[2] == resolution && now[3] == resolution) {
            return;
        }
        this.warnedViewport = true;
        Hypergraphical.LOGGER.warn("[hypergraphical] Probe capture viewport was changed during "
                        + "renderLevel: expected 0,0,{}x{} but found {},{},{}x{}. Reflections "
                        + "will be a crop of the screen corner rather than a real capture - "
                        + "another mod in the render chain is resetting the viewport.",
                resolution, resolution, now[0], now[1], now[2], now[3]);
    }

    private void restoreViewport() {
        GlStateManager._viewport(this.savedViewport[0], this.savedViewport[1],
                this.savedViewport[2], this.savedViewport[3]);
    }

    @Override
    public void close() {
        if (this.environmentShader != null) {
            this.environmentShader.close();
            this.environmentShader = null;
        }
        this.fullscreen.close();
    }
}
