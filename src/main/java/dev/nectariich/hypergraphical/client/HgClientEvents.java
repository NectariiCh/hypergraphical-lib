package dev.nectariich.hypergraphical.client;

import dev.nectariich.hypergraphical.HgConfig;
import dev.nectariich.hypergraphical.Hypergraphical;
import dev.nectariich.hypergraphical.HypergraphicalClient;
import dev.nectariich.hypergraphical.command.HgCommands;
import dev.nectariich.hypergraphical.compat.OculusCompat;
import dev.nectariich.hypergraphical.render.Gl;
import dev.nectariich.hypergraphical.render.HgRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * All client event wiring.
 *
 * <p>Two subscribers with different lifetimes: {@link ModBus} runs once during mod loading to
 * register resource reload listeners, and {@link ForgeBus} runs for the life of the game.
 */
public final class HgClientEvents {

    private HgClientEvents() {}

    // =========================================================================
    // Mod bus: one-time registration
    // =========================================================================
    @Mod.EventBusSubscriber(modid = Hypergraphical.MODID, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {

        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
            // Order is load-bearing. Materials and meshes clear their caches first; the GPU
            // listener then rebuilds placeholders and recompiles shaders, so it must not run
            // before the thing that invalidated them.
            event.registerReloadListener(HypergraphicalClient.materials());
            event.registerReloadListener(HypergraphicalClient.meshes());
            event.registerReloadListener(new GpuReloadListener());
        }
    }

    /**
     * Rebuilds everything that lives on the GPU: placeholder textures, our GLSL programs, and
     * the IBL bake shaders. Runs on the render thread, which is why it is a reload listener
     * rather than something driven from the mod constructor &mdash; there is no GL context yet
     * when mods are being constructed.
     */
    private static final class GpuReloadListener implements ResourceManagerReloadListener {

        @Override
        public void onResourceManagerReload(ResourceManager resourceManager) {
            Gl.init();
            Gl.clearErrors();

            HypergraphicalClient.materials().textures().createPlaceholders();
            HypergraphicalClient.renderer().reload(resourceManager);
            HypergraphicalClient.probes().reload(resourceManager);

            Hypergraphical.LOGGER.info("[hypergraphical] GPU resources reloaded "
                    + "(renderer ready: {})", HypergraphicalClient.renderer().ready());
        }
    }

    // =========================================================================
    // Forge bus: per-frame and per-tick work
    // =========================================================================
    @Mod.EventBusSubscriber(modid = Hypergraphical.MODID, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeBus {

        private ForgeBus() {}

        /**
         * Start of a frame: drain upload queues and advance probe baking.
         *
         * <p>Probe capture <b>has</b> to happen here rather than from a render stage. It calls
         * {@code renderLevel} re-entrantly, and every render stage fires from inside that same
         * method, so capturing from one would recurse.
         */
        @SubscribeEvent
        public static void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.START || !HgConfig.ENABLED.get()) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null || minecraft.player == null) {
                return;
            }

            HgRenderer renderer = HypergraphicalClient.renderer();
            renderer.beginFrame();
            if (!renderer.ready()) {
                return;
            }

            Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().getPosition();
            // Placement first: a probe created this frame starts baking this frame.
            HypergraphicalClient.probes()
                    .updateAutoProbes(HypergraphicalClient.scene(), cameraPos);
            HypergraphicalClient.probes().pumpCaptures(cameraPos, event.renderTickTime);
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            if (Minecraft.getInstance().level == null) {
                return;
            }
            HypergraphicalClient.scene().tick();
            HypergraphicalClient.probes().tick();
        }

        @SubscribeEvent
        public static void onRenderLevelStage(RenderLevelStageEvent event) {
            if (!HgConfig.ENABLED.get()) {
                return;
            }
            HgRenderer renderer = HypergraphicalClient.renderer();
            if (!renderer.ready()) {
                return;
            }

            RenderLevelStageEvent.Stage stage = event.getStage();
            boolean solid = stage == forgeStage(OculusCompat.opaqueStage());
            boolean blend = stage == forgeStage(OculusCompat.blendStage());
            if (!solid && !blend) {
                return;
            }

            // Minecraft's view matrix carries rotation only; it already renders
            // camera-relative, which is exactly the space our instance matrices are in.
            org.joml.Matrix4f view = event.getPoseStack().last().pose();
            Vec3 cameraPos = event.getCamera().getPosition();

            if (solid) {
                renderer.render(HgRenderer.Pass.SOLID, view, event.getProjectionMatrix(),
                        cameraPos, event.getFrustum(), event.getPartialTick());
            }
            if (blend) {
                renderer.render(HgRenderer.Pass.BLEND, view, event.getProjectionMatrix(),
                        cameraPos, event.getFrustum(), event.getPartialTick());
            }
        }

        @SubscribeEvent
        public static void onLevelUnload(LevelEvent.Unload event) {
            if (event.getLevel().isClientSide()) {
                // Instances and probes are tied to world coordinates, so none of them mean
                // anything in the next level.
                HypergraphicalClient.releaseAll();
            }
        }

        @SubscribeEvent
        public static void onRegisterCommands(RegisterClientCommandsEvent event) {
            HgCommands.register(event.getDispatcher());
        }

        @SubscribeEvent
        public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
            if (!HgConfig.DEBUG_OVERLAY.get()) {
                return;
            }
            List<String> left = event.getLeft();
            HgRenderer renderer = HypergraphicalClient.renderer();

            left.add("");
            left.add("[hypergraphical] instances %d/%d  batches %d  draws %d  tris %,d".formatted(
                    renderer.lastInstanceCount(),
                    HypergraphicalClient.scene().size(),
                    renderer.lastBatchCount(),
                    renderer.lastDrawCalls(),
                    renderer.lastTriangles()));
            left.add("[hypergraphical] meshes %d (%d loading)  textures %d (%d loading)".formatted(
                    HypergraphicalClient.meshes().residentCount(),
                    HypergraphicalClient.meshes().pendingCount(),
                    HypergraphicalClient.materials().textures().residentCount(),
                    HypergraphicalClient.materials().textures().pendingCount()));
            left.add("[hypergraphical] probes %d/%d baked (%d auto)  %s  %.1f MB VRAM".formatted(
                    HypergraphicalClient.probes().readyCount(),
                    HypergraphicalClient.probes().count(),
                    HypergraphicalClient.probes().autoProbeCount(),
                    HypergraphicalClient.probes().effectiveStrategy(),
                    HypergraphicalClient.probes().vramBytes() / (1024.0D * 1024.0D)));
        }
    }

    /** Maps our config enum onto Forge's stage constants, which are a class, not an enum. */
    public static RenderLevelStageEvent.Stage forgeStage(HgConfig.Stage stage) {
        return switch (stage) {
            case AFTER_SOLID_BLOCKS -> RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS;
            case AFTER_CUTOUT_BLOCKS -> RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS;
            case AFTER_ENTITIES -> RenderLevelStageEvent.Stage.AFTER_ENTITIES;
            case AFTER_BLOCK_ENTITIES -> RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES;
            case AFTER_TRANSLUCENT_BLOCKS -> RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
            case AFTER_PARTICLES -> RenderLevelStageEvent.Stage.AFTER_PARTICLES;
            case AFTER_WEATHER -> RenderLevelStageEvent.Stage.AFTER_WEATHER;
            case AFTER_LEVEL -> RenderLevelStageEvent.Stage.AFTER_LEVEL;
        };
    }
}
