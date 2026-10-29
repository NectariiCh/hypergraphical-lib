package dev.nectariich.hypergraphical;

import com.mojang.logging.LogUtils;
import dev.nectariich.hypergraphical.util.HgIds;
import net.minecraft.resources.ResourceLocation;
//? if forge {
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
//?} else {
/*import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
*///?}
import org.slf4j.Logger;

/**
 * Hypergraphical Lib &mdash; Unity-style runtime model rendering for Minecraft.
 *
 * <p>The mod is deliberately client-heavy: everything interesting lives in a self-contained
 * OpenGL pass dispatched from {@code RenderLevelStageEvent}, which never touches the vanilla
 * {@code BakedModel} pipeline. That is what makes arbitrary-coordinate rendering, PBR
 * materials and real cubemap reflections possible at all &mdash; the vanilla block pipeline has
 * nowhere to put a metallic value, let alone a reflection probe.
 *
 * <p>Entry point for consumers is {@link dev.nectariich.hypergraphical.api.HypergraphicalAPI}.
 */
@Mod(Hypergraphical.MODID)
public final class Hypergraphical {

    public static final String MODID = "hypergraphical";
    public static final Logger LOGGER = LogUtils.getLogger();

    //? if forge {
    public Hypergraphical() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, HgConfig.SPEC, MODID + "-client.toml");
        init();
    }
    //?} else {
    /*public Hypergraphical(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, HgConfig.SPEC, MODID + "-client.toml");
        init();
    }
    *///?}

    private static void init() {
        // HypergraphicalClient is only resolved when this branch runs, so a dedicated server
        // never classloads anything that touches GL. This is what DistExecutor did, and it
        // works the same on both loaders.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            HypergraphicalClient.bootstrap();
        }

        LOGGER.info("Hypergraphical Lib loading");
    }

    /** Shorthand for {@code hypergraphical:path}. */
    public static ResourceLocation id(String path) {
        return HgIds.of(MODID, path);
    }
}
