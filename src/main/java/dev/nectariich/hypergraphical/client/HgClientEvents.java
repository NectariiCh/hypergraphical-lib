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
//? if forge {
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
//?} else {
/*import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
*///?}

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
    //? if forge {
    @Mod.EventBusSubscriber(modid = Hypergraphical.MODID, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    //?} else {
    /*@EventBusSubscriber(modid = Hypergraphical.MODID, value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.MOD)
    *///?}
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
    //? if forge {
    @Mod.EventBusSubscriber(modid = Hypergraphical.MODID, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.FORGE)
    //?} else {
    /*@EventBusSubscriber(modid = Hypergraphical.MODID, value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.GAME)
    *///?}
    public static final class ForgeBus {

        private ForgeBus() {}

        /**
         * Start of a frame: drain upload queues and advance probe baking.
         *
         * <p>Probe capture <b>has</b> to happen here rather than from a render stage. It calls
         * {@code renderLevel} re-entrantly, and every render stage fires from inside that same
         * method, so capturing from one would recurse.
         */
        //? if forge {
        @SubscribeEvent
        public static void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                beginFrame(event.renderTickTime);
            }
        }
        //?} else {
        /*@SubscribeEvent
        public static void onRenderFrame(RenderFrameEvent.Pre event) {
            beginFrame(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }
        *///?}

        private static void beginFrame(float partialTick) {
            if (!HgConfig.ENABLED.get()) {
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
            HypergraphicalClient.probes().pumpCaptures(cameraPos, partialTick);
        }

        //? if forge {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                endClientTick();
            }
        }
        //?} else {
        /*@SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            endClientTick();
        }
        *///?}

        private static void endClientTick() {
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
            org.joml.Matrix4f view = viewMatrix(event);
            Vec3 cameraPos = event.getCamera().getPosition();
            float partialTick = partialTick(event);

            if (solid) {
                renderer.render(HgRenderer.Pass.SOLID, view, event.getProjectionMatrix(),
                        cameraPos, event.getFrustum(), partialTick);
            }
            if (blend) {
                renderer.render(HgRenderer.Pass.BLEND, view, event.getProjectionMatrix(),
                        cameraPos, event.getFrustum(), partialTick);
            }
        }

        //? if forge {
        private static org.joml.Matrix4f viewMatrix(RenderLevelStageEvent event) {
            return event.getPoseStack().last().pose();
        }

        private static float partialTick(RenderLevelStageEvent event) {
            return event.getPartialTick();
        }
        //?} else {
        /*// NeoForge passes no usable pose stack here: it is null for the block-layer stages
        // and a fresh identity stack for the rest. The view rotation is the model-view matrix.
        private static org.joml.Matrix4f viewMatrix(RenderLevelStageEvent event) {
            return event.getModelViewMatrix();
        }

        private static float partialTick(RenderLevelStageEvent event) {
            return event.getPartialTick().getGameTimeDeltaPartialTick(false);
        }
        *///?}

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
