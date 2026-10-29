package dev.nectariich.hypergraphical.compat;

import dev.nectariich.hypergraphical.Hypergraphical;
import net.minecraftforge.fml.ModList;

/**
 * Embeddium detection and notes on what our pass assumes about it.
 *
 * <p>Embeddium is a <b>hard dependency</b> declared in {@code mods.toml}, so by the time this
 * class runs it is present; the checks here exist to log what we are running against and to
 * fail loudly rather than mysteriously if someone force-loads without it.
 *
 * <p>What we rely on Embeddium for, and why the pass stays compatible:
 *
 * <ul>
 *   <li><b>A GL 3.3+ core context.</b> Embeddium already requires more than Minecraft's 3.2
 *       baseline, which is what lets us use instanced arrays unconditionally.</li>
 *   <li><b>Forge's render events still firing.</b> Embeddium replaces terrain chunk rendering
 *       but keeps {@code RenderLevelStageEvent}, so we hook the same stages as on vanilla
 *       Forge. We never touch its chunk meshing, its vertex formats or its shaders.</li>
 *   <li><b>Nothing shared but the framebuffer and the depth buffer.</b> Our geometry is drawn
 *       with our own VAOs, buffers and program into the main render target, so terrain
 *       occludes our models and our models occlude terrain through ordinary depth testing.
 *       Every piece of GL state we touch is saved and restored (see {@code GlStateGuard}),
 *       which is the actual requirement for coexisting with a renderer replacement.</li>
 * </ul>
 */
public final class EmbeddiumCompat {

    //? if forge {
    private static final String EMBEDDIUM = "embeddium";
    private static final String NAME = "Embeddium";
    //?} else {
    /*// Sodium is the required renderer on 1.21.1: Embeddium stopped updating for NeoForge
    // 1.21.1 and Iris refuses to run with it. Everything below holds for Sodium as well.
    private static final String EMBEDDIUM = "sodium";
    private static final String NAME = "Sodium";
    *///?}
    /** Embeddium's predecessor; some packs still ship it. It is not a substitute. */
    private static final String RUBIDIUM = "rubidium";

    private static Boolean present;

    private EmbeddiumCompat() {}

    public static boolean isLoaded() {
        if (present == null) {
            present = ModList.get().isLoaded(EMBEDDIUM);
        }
        return present;
    }

    public static void logStatus() {
        if (isLoaded()) {
            String version = ModList.get().getModContainerById(EMBEDDIUM)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
            Hypergraphical.LOGGER.info("[hypergraphical] {} {} detected", NAME, version);
            return;
        }

        if (ModList.get().isLoaded(RUBIDIUM)) {
            Hypergraphical.LOGGER.error("[hypergraphical] Rubidium is installed but Embeddium is "
                    + "not. Hypergraphical requires Embeddium; Rubidium is its unmaintained "
                    + "predecessor and is not a drop-in replacement.");
        } else {
            Hypergraphical.LOGGER.error("[hypergraphical] {} is not loaded. It is a hard "
                    + "dependency and the loader should have refused to start - expect problems.", NAME);
        }
    }
}
