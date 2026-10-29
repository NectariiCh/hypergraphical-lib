package dev.nectariich.hypergraphical.compat;

import dev.nectariich.hypergraphical.HgConfig;
import dev.nectariich.hypergraphical.Hypergraphical;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * Oculus (Iris for Forge) integration.
 *
 * <p>Reached entirely through reflection, deliberately. Oculus has no stable Forge-side
 * artifact for the 1.20.1 line and its internals move between releases; compiling against it
 * would mean our jar refuses to load whenever it bumps, for the sake of a single boolean.
 * {@code IrisApi} is the one part it declares as stable, so we ask that and nothing else.
 *
 * <h2>What changes when a shader pack is active</h2>
 *
 * <ul>
 *   <li><b>Probe capture downgrades to procedural environment.</b> Iris' deferred pipeline is
 *       not re-entrant: calling {@code renderLevel} again from inside a frame would restart
 *       its shadow and gbuffer passes with the wrong uniforms. This is the honest limitation
 *       of the design and the reason for the fallback rather than a workaround.</li>
 *   <li><b>The pass can move to a later stage.</b> Our geometry is forward-shaded with its
 *       own program, so it cannot write into Iris' gbuffers. Drawing after the translucent
 *       stage puts it into the composited image, where it reads correctly against
 *       shader-pack terrain instead of being re-lit by a pack that knows nothing about it.
 *       This means our models do not receive a pack's shadows or its sky lighting; that is
 *       inherent to a mod-owned forward pass, not something a stage change can fix.</li>
 * </ul>
 */
public final class OculusCompat {

    //? if forge {
    private static final String OCULUS = "oculus";
    private static final String NAME = "Oculus";
    //?} else {
    /*// Oculus has no 1.21.1 build; Iris itself runs on NeoForge there, with the same IrisApi.
    private static final String OCULUS = "iris";
    private static final String NAME = "Iris";
    *///?}
    private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";

    private static Boolean present;
    private static boolean resolved;
    private static Object apiInstance;
    private static Method shaderPackInUse;
    private static boolean warnedReflectionFailure;

    private OculusCompat() {}

    public static boolean isLoaded() {
        if (present == null) {
            present = ModList.get().isLoaded(OCULUS);
        }
        return present;
    }

    /**
     * Whether a shader pack is currently rendering. {@code false} when Oculus is absent, when
     * no pack is enabled, or when the API could not be reached.
     */
    public static boolean shaderPackInUse() {
        if (!isLoaded()) {
            return false;
        }
        resolve();
        if (shaderPackInUse == null || apiInstance == null) {
            return false;
        }
        try {
            return (Boolean) shaderPackInUse.invoke(apiInstance);
        } catch (Exception e) {
            if (!warnedReflectionFailure) {
                warnedReflectionFailure = true;
                Hypergraphical.LOGGER.warn("[hypergraphical] IrisApi#isShaderPackInUse failed; "
                        + "assuming no shader pack. {}", e.toString());
            }
            return false;
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> api = Class.forName(IRIS_API);
            apiInstance = api.getMethod("getInstance").invoke(null);
            shaderPackInUse = api.getMethod("isShaderPackInUse");
            Hypergraphical.LOGGER.info("[hypergraphical] {} detected; IrisApi bound", NAME);
        } catch (Exception e) {
            Hypergraphical.LOGGER.warn("[hypergraphical] {} is present but IrisApi could not "
                    + "be bound ({}). Shader-pack-aware behaviour is disabled.", NAME, e.toString());
        }
    }

    /** Stage for opaque geometry, honouring the shader-pack override. */
    public static HgConfig.Stage opaqueStage() {
        return shaderPackInUse() ? HgConfig.OCULUS_OPAQUE_STAGE.get() : HgConfig.OPAQUE_STAGE.get();
    }

    /** Stage for blended geometry, honouring the shader-pack override. */
    public static HgConfig.Stage blendStage() {
        return shaderPackInUse() ? HgConfig.OCULUS_BLEND_STAGE.get() : HgConfig.BLEND_STAGE.get();
    }

    public static void logStatus() {
        if (isLoaded()) {
            String version = ModList.get().getModContainerById(OCULUS)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
            Hypergraphical.LOGGER.info("[hypergraphical] {} {} present - probe capture will "
                    + "fall back to procedural environment while a pack is enabled", NAME, version);
        }
    }
}
