package dev.nectariich.hypergraphical;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
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

    public Hypergraphical() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, HgConfig.SPEC, MODID + "-client.toml");

        // Client bootstrap goes through DistExecutor so a dedicated server never classloads
        // anything that touches GL.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> HypergraphicalClient::bootstrap);

        LOGGER.info("Hypergraphical Lib loading");
    }

    /** Shorthand for {@code hypergraphical:path}. */
    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }
}
