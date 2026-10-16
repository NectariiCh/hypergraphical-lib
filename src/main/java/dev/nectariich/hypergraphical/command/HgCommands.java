package dev.nectariich.hypergraphical.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.nectariich.hypergraphical.HypergraphicalClient;
import dev.nectariich.hypergraphical.api.HypergraphicalAPI;
import dev.nectariich.hypergraphical.api.ModelInstance;
import dev.nectariich.hypergraphical.mesh.MeshManager;
import dev.nectariich.hypergraphical.reflect.ReflectionProbe;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Client-side {@code /hg} commands.
 *
 * <p>Not a toy: placing a model at typed coordinates and baking a probe on the spot is the
 * fastest way to check that arbitrary-coordinate rendering, a material and a reflection are
 * all behaving, without writing a test mod around the API first.
 *
 * <pre>
 *   /hg spawn &lt;mesh&gt; [material] [x y z]   place a model (defaults to where you stand)
 *   /hg clear                              remove every instance
 *   /hg probe add [x y z] [radius]         add a reflection probe
 *   /hg probe rebake                       force every probe to re-capture
 *   /hg probe clear                        remove every probe
 *   /hg probe list                         list probes and their state
 *   /hg stats                              renderer counters
 *   /hg reload                             recompile our shaders and re-bake probes
 * </pre>
 */
public final class HgCommands {

    private HgCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hg")
                .then(spawn())
                .then(Commands.literal("clear").executes(HgCommands::clear))
                .then(probe())
                .then(Commands.literal("stats").executes(HgCommands::stats))
                .then(Commands.literal("reload").executes(HgCommands::reload)));
    }

    // ---------------------------------------------------------------------- spawn
    private static LiteralArgumentBuilder<CommandSourceStack> spawn() {
        return Commands.literal("spawn")
                .then(Commands.argument("mesh", ResourceLocationArgument.id())
                        // Suggesting what exists is the real fix for mistyped mesh names; the
                        // existence check in doSpawn is the safety net behind it.
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(
                                MeshManager.available(), builder))
                        .executes(ctx -> doSpawn(ctx, null, null, 1.0F))
                        .then(Commands.argument("material", ResourceLocationArgument.id())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(
                                        HypergraphicalClient.materials().ids(), builder))
                                .executes(ctx -> doSpawn(ctx,
                                        ResourceLocationArgument.getId(ctx, "material"), null, 1.0F))
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(ctx -> doSpawn(ctx,
                                                ResourceLocationArgument.getId(ctx, "material"),
                                                Vec3Argument.getVec3(ctx, "pos"), 1.0F))
                                        .then(Commands.argument("scale", FloatArgumentType.floatArg(0.001F, 1000.0F))
                                                .executes(ctx -> doSpawn(ctx,
                                                        ResourceLocationArgument.getId(ctx, "material"),
                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                        FloatArgumentType.getFloat(ctx, "scale")))))));
    }

    private static int doSpawn(CommandContext<CommandSourceStack> ctx,
                               @Nullable ResourceLocation material,
                               @Nullable Vec3 position, float scale) {
        ResourceLocation mesh = ResourceLocationArgument.getId(ctx, "mesh");
        Vec3 where = position != null ? position : ctx.getSource().getPosition();

        // Loading is asynchronous and a missing mesh only ever produced a log warning, so the
        // command used to report a cheerful "Spawned" for a model that could never appear.
        if (!MeshManager.exists(mesh)) {
            ctx.getSource().sendFailure(Component.literal("No such mesh: " + mesh)
                    .withStyle(ChatFormatting.RED));
            var available = MeshManager.available();
            reply(ctx, ChatFormatting.GRAY, available.isEmpty()
                    ? "No .obj files found under models/obj in any loaded pack."
                    : "Available: " + available.stream().map(ResourceLocation::toString)
                            .reduce((a, b) -> a + ", " + b).orElse(""));
            return 0;
        }

        ModelInstance instance = HypergraphicalAPI.spawn(mesh, material, where);
        instance.setScale(scale);

        reply(ctx, ChatFormatting.GREEN, "Spawned %s at %.2f %.2f %.2f (instance #%d)%s"
                .formatted(mesh, where.x, where.y, where.z, instance.id(),
                        material == null ? " using its own .mtl materials" : " with " + material));
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        int removed = HypergraphicalAPI.instanceCount();
        HypergraphicalAPI.clearAll();
        reply(ctx, ChatFormatting.YELLOW, "Removed " + removed + " instance(s)");
        return removed;
    }

    // ---------------------------------------------------------------------- probes
    private static LiteralArgumentBuilder<CommandSourceStack> probe() {
        return Commands.literal("probe")
                .then(Commands.literal("add")
                        .executes(ctx -> addProbe(ctx, null, 16.0D))
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> addProbe(ctx, Vec3Argument.getVec3(ctx, "pos"), 16.0D))
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1.0D, 256.0D))
                                        .executes(ctx -> addProbe(ctx, Vec3Argument.getVec3(ctx, "pos"),
                                                DoubleArgumentType.getDouble(ctx, "radius"))))))
                .then(Commands.literal("rebake").executes(ctx -> {
                    HypergraphicalAPI.rebakeProbes();
                    reply(ctx, ChatFormatting.YELLOW, "Invalidated %d probe(s); they will re-capture"
                            .formatted(HypergraphicalClient.probes().count()));
                    return 1;
                }))
                .then(Commands.literal("clear").executes(ctx -> {
                    int count = HypergraphicalClient.probes().count();
                    HypergraphicalAPI.clearProbes();
                    reply(ctx, ChatFormatting.YELLOW, "Removed " + count + " probe(s)");
                    return count;
                }))
                .then(Commands.literal("list").executes(HgCommands::listProbes));
    }

    private static int addProbe(CommandContext<CommandSourceStack> ctx,
                                @Nullable Vec3 position, double radius) {
        Vec3 where = position != null ? position : ctx.getSource().getPosition();
        ReflectionProbe probe = HypergraphicalAPI.addProbe(where, radius);
        reply(ctx, ChatFormatting.GREEN, "Added probe #%d at %.2f %.2f %.2f, radius %.1f (%s capture)"
                .formatted(probe.id(), where.x, where.y, where.z, radius,
                        HypergraphicalAPI.activeProbeStrategy()));
        return 1;
    }

    private static int listProbes(CommandContext<CommandSourceStack> ctx) {
        var probes = HypergraphicalClient.probes().probes();
        if (probes.isEmpty()) {
            reply(ctx, ChatFormatting.GRAY, "No probes. Add one with /hg probe add");
            return 0;
        }
        reply(ctx, ChatFormatting.AQUA, "%d probe(s), strategy %s:"
                .formatted(probes.size(), HypergraphicalAPI.activeProbeStrategy()));
        for (ReflectionProbe probe : probes) {
            reply(ctx, ChatFormatting.GRAY, "  " + probe);
        }
        return probes.size();
    }

    // ----------------------------------------------------------------------- info
    private static int stats(CommandContext<CommandSourceStack> ctx) {
        var renderer = HypergraphicalClient.renderer();
        var meshes = HypergraphicalClient.meshes();
        var textures = HypergraphicalClient.materials().textures();
        var probes = HypergraphicalClient.probes();

        reply(ctx, ChatFormatting.AQUA, "Hypergraphical");
        reply(ctx, ChatFormatting.GRAY, "  shader ready: " + renderer.ready());
        reply(ctx, ChatFormatting.GRAY, "  instances: %d total, %d drawn last frame"
                .formatted(HypergraphicalClient.scene().size(), renderer.lastInstanceCount()));
        reply(ctx, ChatFormatting.GRAY, "  batches: %d, draw calls: %d, triangles: %,d"
                .formatted(renderer.lastBatchCount(), renderer.lastDrawCalls(),
                        renderer.lastTriangles()));
        reply(ctx, ChatFormatting.GRAY, "  meshes: %d resident (%d loading), %,d triangles"
                .formatted(meshes.residentCount(), meshes.pendingCount(),
                        meshes.residentTriangles()));
        reply(ctx, ChatFormatting.GRAY, "  textures: %d resident (%d loading)"
                .formatted(textures.residentCount(), textures.pendingCount()));
        reply(ctx, ChatFormatting.GRAY, "  materials: %d"
                .formatted(HypergraphicalClient.materials().count()));
        reply(ctx, ChatFormatting.GRAY, "  probes: %d/%d baked (%d placed automatically)"
                .formatted(probes.readyCount(), probes.count(), probes.autoProbeCount()));
        reply(ctx, ChatFormatting.GRAY, "  probe VRAM: %.1f MB, strategy %s"
                .formatted(probes.vramBytes() / (1024.0D * 1024.0D), probes.effectiveStrategy()));
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        var resources = Minecraft.getInstance().getResourceManager();
        HypergraphicalClient.renderer().reload(resources);
        HypergraphicalClient.probes().reload(resources);
        reply(ctx, ChatFormatting.GREEN, "Recompiled shaders (ready: %s) and invalidated probes"
                .formatted(HypergraphicalClient.renderer().ready()));
        return 1;
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, ChatFormatting color,
                              String message) {
        ctx.getSource().sendSuccess(() -> Component.literal(message).withStyle(color), false);
    }
}
