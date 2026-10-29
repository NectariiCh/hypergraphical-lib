package dev.nectariich.hypergraphical;

// The spec class is renamed for 1.21.1 by a Stonecutter replacement (stonecutter.gradle.kts).
//? if forge {
import net.minecraftforge.common.ForgeConfigSpec;
//?} else {
/*import net.neoforged.neoforge.common.ForgeConfigSpec;
*///?}

/**
 * Client configuration. Every value that can cost frames is exposed here, because the
 * sensible default for integrated graphics and for a 4090 are not the same number.
 */
public final class HgConfig {

    /** How reflection probes acquire their environment. */
    public enum ProbeCapture {
        /**
         * Re-render the real level six times into a cubemap. A true reflection of the world
         * &mdash; terrain, entities, sky &mdash; not a screen-space approximation.
         */
        LEVEL,
        /**
         * Procedural sky/fog/sun cubemap derived from the current biome and time of day.
         * Cheap, always safe, and the automatic fallback when LEVEL capture is not viable
         * (active Oculus shader pack, Fabulous graphics, or a capture that threw).
         */
        ENVIRONMENT,
        /** No probes. Specular falls back to a flat ambient term. */
        OFF
    }

    /** Which {@code RenderLevelStageEvent} stage a pass is dispatched from. */
    public enum Stage {
        AFTER_SOLID_BLOCKS,
        AFTER_CUTOUT_BLOCKS,
        AFTER_ENTITIES,
        AFTER_BLOCK_ENTITIES,
        AFTER_TRANSLUCENT_BLOCKS,
        AFTER_PARTICLES,
        AFTER_WEATHER,
        AFTER_LEVEL
    }

    private static final ForgeConfigSpec.Builder B = new ForgeConfigSpec.Builder();

    // ------------------------------------------------------------------ general
    public static final ForgeConfigSpec.BooleanValue ENABLED = B
            .comment("Master switch for the Hypergraphical render pass.")
            .define("general.enabled", true);

    public static final ForgeConfigSpec.BooleanValue DEBUG_OVERLAY = B
            .comment("Append instance/batch/probe counters to the F3 debug text.")
            .define("general.debugOverlay", false);

    public static final ForgeConfigSpec.BooleanValue GL_DEBUG = B
            .comment("Check glGetError after every stage of our pass. Costs a pipeline flush",
                     "per check, so dev only. Also enabled by -Dhypergraphical.glDebug=true.")
            .define("general.glDebugChecks", false);

    // ---------------------------------------------------------------- rendering
    public static final ForgeConfigSpec.DoubleValue MAX_RENDER_DISTANCE = B
            .comment("Instances further than this (blocks, from the camera) are skipped.")
            .defineInRange("render.maxRenderDistance", 192.0D, 16.0D, 1024.0D);

    public static final ForgeConfigSpec.IntValue MAX_INSTANCES_PER_FRAME = B
            .comment("Hard cap on instances submitted per frame, after culling. Protects",
                     "against a runaway spawn loop taking the client down.")
            .defineInRange("render.maxInstancesPerFrame", 40000, 64, 1000000);

    public static final ForgeConfigSpec.BooleanValue FRUSTUM_CULLING = B
            .comment("Cull instances against the camera frustum using their world AABB.")
            .define("render.frustumCulling", true);

    public static final ForgeConfigSpec.EnumValue<Stage> OPAQUE_STAGE = B
            .comment("Stage for opaque and cutout geometry. Must be before translucent blocks",
                     "so water and glass sort correctly in front of our models.")
            .defineEnum("render.opaqueStage", Stage.AFTER_BLOCK_ENTITIES);

    public static final ForgeConfigSpec.EnumValue<Stage> BLEND_STAGE = B
            .comment("Stage for alpha-blended geometry. Must be after translucent blocks.")
            .defineEnum("render.blendStage", Stage.AFTER_PARTICLES);

    // ------------------------------------------------------------------ shading
    public static final ForgeConfigSpec.BooleanValue SUN_SPECULAR = B
            .comment("Add an analytic GGX highlight from the sun/moon direction, gated by",
                     "skylight. Minecraft has no real directional light, so without this a",
                     "smooth metal only shows its probe reflection and reads as flat.")
            .define("shading.sunSpecular", true);

    public static final ForgeConfigSpec.DoubleValue IBL_DIFFUSE = B
            .comment("Strength of probe irradiance in the diffuse term. 0 = pure vanilla lightmap.")
            .defineInRange("shading.iblDiffuseStrength", 1.0D, 0.0D, 2.0D);

    public static final ForgeConfigSpec.DoubleValue IBL_SPECULAR = B
            .comment("Strength of probe radiance in the specular term.")
            .defineInRange("shading.iblSpecularStrength", 1.0D, 0.0D, 2.0D);

    public static final ForgeConfigSpec.BooleanValue APPLY_FOG = B
            .comment("Blend our geometry into vanilla fog. Off makes distant models pop",
                     "out of the fog wall.")
            .define("shading.applyFog", true);

    // ------------------------------------------------------------------- probes
    public static final ForgeConfigSpec.EnumValue<ProbeCapture> PROBE_CAPTURE = B
            .comment("How probes acquire their environment cubemap.")
            .defineEnum("probes.capture", ProbeCapture.LEVEL);

    public static final ForgeConfigSpec.BooleanValue PROBE_AUTO = B
            .comment("Place probes automatically: one per grid cell that contains instances,",
                     "so models are reflective without anyone hand-placing anything. Manual",
                     "probes from the API or /hg probe add still work alongside these.")
            .define("probes.automatic", true);

    public static final ForgeConfigSpec.IntValue PROBE_AUTO_CELL = B
            .comment("Edge length in blocks of the auto-placement grid. Smaller cells mean",
                     "more probes, more bakes, and more accurate parallax; larger cells mean",
                     "instances further from their probe's capture point.")
            .defineInRange("probes.autoCellSize", 24, 4, 128);

    public static final ForgeConfigSpec.IntValue PROBE_AUTO_MAX = B
            .comment("Cap on automatically placed probes. Separate from probes.maxActive,",
                     "which caps how many hold GPU memory; this caps how many exist.")
            .defineInRange("probes.autoMaxProbes", 16, 1, 128);

    public static final ForgeConfigSpec.IntValue PROBE_RESOLUTION = B
            .comment("Per-face resolution of the radiance cubemap. Rounded down to a power of",
                     "two and clamped to the window's shorter side.")
            .defineInRange("probes.resolution", 128, 16, 1024);

    public static final ForgeConfigSpec.IntValue PROBE_FACES_PER_FRAME = B
            .comment("Cubemap faces captured per frame. 1 means a full probe takes six frames,",
                     "which is what keeps LEVEL capture affordable.")
            .defineInRange("probes.facesPerFrame", 1, 1, 6);

    public static final ForgeConfigSpec.IntValue PROBE_MAX_ACTIVE = B
            .comment("Maximum probes holding GPU cubemaps at once. The nearest N to the camera",
                     "win; the rest are evicted and re-baked on approach.")
            .defineInRange("probes.maxActive", 8, 1, 64);

    public static final ForgeConfigSpec.IntValue PROBE_REFRESH_TICKS = B
            .comment("Re-bake every probe every N ticks. 0 = bake once and keep, which is",
                     "correct for static scenes and by far the cheapest.")
            .defineInRange("probes.refreshTicks", 0, 0, 72000);

    public static final ForgeConfigSpec.IntValue PROBE_CAPTURE_DISTANCE = B
            .comment("How far a reflection probe can see, in chunks. This is the far plane of",
                     "the capture frustum, so it is what decides how much of the world ends up",
                     "in a reflection. Lower is faster and gets blurrier the rougher the",
                     "material, but a distant skyline simply will not be there.",
                     "",
                     "Values below 9 are floored to 144 blocks for the capture itself:",
                     "Minecraft draws the sun and moon 100 blocks out, and a shorter far plane",
                     "clips them out of every reflection.")
            .defineInRange("probes.captureRenderDistance", 12, 2, 32);

    public static final ForgeConfigSpec.BooleanValue PROBE_PARALLAX_BOX = B
            .comment("Correct the reflection vector against each probe's box, the way Unity's",
                     "box projection does. Without it a probe is only right at its centre.")
            .define("probes.boxProjection", true);

    public static final ForgeConfigSpec.BooleanValue PROBE_BLEND = B
            .comment("Blend the two nearest probes per instance instead of snapping to one.")
            .define("probes.blendNearest", true);

    // ------------------------------------------------------------------- compat
    public static final ForgeConfigSpec.BooleanValue OCULUS_DEFER = B
            .comment("When an Oculus shader pack is active, downgrade probe capture to",
                     "ENVIRONMENT. Re-entrant level rendering is not safe under Iris.")
            .define("compat.downgradeProbesUnderShaders", true);

    public static final ForgeConfigSpec.EnumValue<Stage> OCULUS_OPAQUE_STAGE = B
            .comment("Stage override for opaque geometry while a shader pack is active.")
            .defineEnum("compat.oculusOpaqueStage", Stage.AFTER_BLOCK_ENTITIES);

    public static final ForgeConfigSpec.EnumValue<Stage> OCULUS_BLEND_STAGE = B
            .comment("Stage override for blended geometry while a shader pack is active.")
            .defineEnum("compat.oculusBlendStage", Stage.AFTER_TRANSLUCENT_BLOCKS);

    public static final ForgeConfigSpec SPEC = B.build();

    private HgConfig() {}

    /** Largest power of two that is {@code <= v}. */
    public static int floorPow2(int v) {
        return Math.max(1, Integer.highestOneBit(Math.max(1, v)));
    }

    public static boolean glDebug() {
        return GL_DEBUG.get() || Boolean.getBoolean("hypergraphical.glDebug");
    }
}
