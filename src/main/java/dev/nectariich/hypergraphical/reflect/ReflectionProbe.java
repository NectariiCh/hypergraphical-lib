package dev.nectariich.hypergraphical.reflect;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * A reflection probe: a point in the world whose surroundings are captured into a cubemap and
 * then convolved into the two maps a PBR shader needs.
 *
 * <p>Follows Unity's model closely, because it is the right one:
 * <ul>
 *   <li>A <b>capture point</b> and a <b>box of influence</b>. Instances inside the box use the
 *       probe; the box is also the proxy geometry for box projection.</li>
 *   <li><b>Box projection</b> re-intersects the reflection ray with the box before sampling,
 *       so a wall reflected in a floor stays put as you walk. Without it a cubemap is only
 *       correct at the exact capture point, and a large probe looks like the reflection is
 *       painted on and sliding.</li>
 *   <li>A <b>refresh mode</b>: bake once, or re-bake on an interval.</li>
 * </ul>
 *
 * <p>Capture is spread over frames. {@link #pendingFace} walks 0..5, and only once all six
 * faces have landed is {@link #ready} set and the convolutions run. A probe therefore costs
 * six extra level renders spread over six or more frames, once, and then nothing.
 */
public final class ReflectionProbe {

    public enum Mode {
        /** Capture once, then never again unless explicitly invalidated. */
        BAKE_ONCE,
        /** Re-capture on the interval in {@code probes.refreshTicks}. */
        REALTIME
    }

    private static int nextId = 0;

    private final int id;
    private Vec3 center;
    private Vec3 halfExtents;
    private Mode mode;

    /** Raw environment capture. */
    @Nullable private Cubemap radiance;
    /** GGX-prefiltered radiance; mip level encodes roughness. */
    @Nullable private Cubemap prefiltered;
    /** Cosine-convolved irradiance for the diffuse term. */
    @Nullable private Cubemap irradiance;

    private int pendingFace;
    private boolean ready;
    private boolean capturing;
    private int lastBakeTick = Integer.MIN_VALUE;
    private int resolution;

    /** Set when LEVEL capture failed for this probe, so it is not retried every frame. */
    private boolean levelCaptureFailed;

    /**
     * Whether the radiance cubemap currently holds gamma-encoded values.
     *
     * <p>A LEVEL capture is a copy of Minecraft's framebuffer and is therefore in screen
     * (gamma) space; the procedural ENVIRONMENT shader writes linear. The convolutions have to
     * know which, because the decode has to happen exactly once. Whichever strategy fills a
     * face records it here.
     */
    private boolean radianceSrgb;

    public ReflectionProbe(Vec3 center, Vec3 halfExtents, int resolution, Mode mode) {
        this.id = nextId++;
        this.center = center;
        this.halfExtents = halfExtents;
        this.resolution = resolution;
        this.mode = mode;
    }

    public int id() {
        return this.id;
    }

    public Vec3 center() {
        return this.center;
    }

    public Vec3 halfExtents() {
        return this.halfExtents;
    }

    public Mode mode() {
        return this.mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public int resolution() {
        return this.resolution;
    }

    public AABB box() {
        return new AABB(
                this.center.x - this.halfExtents.x, this.center.y - this.halfExtents.y,
                this.center.z - this.halfExtents.z,
                this.center.x + this.halfExtents.x, this.center.y + this.halfExtents.y,
                this.center.z + this.halfExtents.z);
    }

    public boolean contains(double x, double y, double z) {
        return Math.abs(x - this.center.x) <= this.halfExtents.x
                && Math.abs(y - this.center.y) <= this.halfExtents.y
                && Math.abs(z - this.center.z) <= this.halfExtents.z;
    }

    /** Squared distance from a point to the probe centre, for nearest-probe selection. */
    public double distanceSqTo(double x, double y, double z) {
        double dx = x - this.center.x;
        double dy = y - this.center.y;
        double dz = z - this.center.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public void move(Vec3 center, Vec3 halfExtents) {
        this.center = center;
        this.halfExtents = halfExtents;
        invalidate();
    }

    public void setResolution(int resolution) {
        if (this.resolution != resolution) {
            this.resolution = resolution;
            releaseTextures();
            invalidate();
        }
    }

    // ----------------------------------------------------------------- lifecycle
    /** Drops the baked result so the probe re-captures from face 0. */
    public void invalidate() {
        this.ready = false;
        this.pendingFace = 0;
        this.capturing = false;
        this.levelCaptureFailed = false;
    }

    public boolean ready() {
        return this.ready;
    }

    public boolean capturing() {
        return this.capturing;
    }

    public int pendingFace() {
        return this.pendingFace;
    }

    public boolean levelCaptureFailed() {
        return this.levelCaptureFailed;
    }

    public void markLevelCaptureFailed() {
        this.levelCaptureFailed = true;
    }

    /** See {@link #radianceSrgb}. Set by whichever capture strategy filled a face. */
    public boolean radianceIsSrgb() {
        return this.radianceSrgb;
    }

    void setRadianceIsSrgb(boolean srgb) {
        this.radianceSrgb = srgb;
    }

    /** Whether this probe wants a face captured on this tick. */
    public boolean needsCapture(int tick, int refreshTicks) {
        if (this.capturing) {
            return true;
        }
        if (!this.ready) {
            return true;
        }
        if (this.mode == ReflectionProbe.Mode.REALTIME && refreshTicks > 0) {
            return tick - this.lastBakeTick >= refreshTicks;
        }
        return false;
    }

    void beginCapture() {
        this.capturing = true;
        this.pendingFace = 0;
        this.ready = false;
    }

    void faceCaptured() {
        this.pendingFace++;
    }

    boolean allFacesCaptured() {
        return this.pendingFace >= 6;
    }

    void finishCapture(int tick) {
        this.capturing = false;
        this.pendingFace = 0;
        this.ready = true;
        this.lastBakeTick = tick;
    }

    // ------------------------------------------------------------------ textures
    Cubemap radiance() {
        if (this.radiance == null) {
            this.radiance = new Cubemap(this.resolution, true, false);
        }
        return this.radiance;
    }

    Cubemap prefiltered() {
        if (this.prefiltered == null) {
            this.prefiltered = new Cubemap(this.resolution, true, false);
        }
        return this.prefiltered;
    }

    Cubemap irradiance() {
        if (this.irradiance == null) {
            // Mipped so the shader can read the top 1x1 mip as a global average and use
            // irradiance as a brightness-neutral directional tint. See hgIrradianceTint.
            this.irradiance = new Cubemap(IblProcessor.IRRADIANCE_SIZE, true, false);
        }
        return this.irradiance;
    }

    /**
     * The three cubemaps without allocating one that does not exist yet.
     *
     * <p>{@link #radiance()} and friends allocate on first call, which is right for the
     * capture path and wrong for anything that only wants to look. Diagnostics use these.
     */
    @Nullable
    Cubemap rawRadiance() {
        return this.radiance;
    }

    @Nullable
    Cubemap rawPrefiltered() {
        return this.prefiltered;
    }

    @Nullable
    Cubemap rawIrradiance() {
        return this.irradiance;
    }

    /** GL texture id of the prefiltered radiance map, or 0 if not baked. */
    public int prefilteredTexture() {
        return this.ready && this.prefiltered != null ? this.prefiltered.texture() : 0;
    }

    /** GL texture id of the irradiance map, or 0 if not baked. */
    public int irradianceTexture() {
        return this.ready && this.irradiance != null ? this.irradiance.texture() : 0;
    }

    public int prefilteredMipLevels() {
        return this.prefiltered != null ? this.prefiltered.mipLevels() : 1;
    }

    public int irradianceMipLevels() {
        return this.irradiance != null ? this.irradiance.mipLevels() : 1;
    }

    public long vramBytes() {
        long total = 0L;
        if (this.radiance != null) total += this.radiance.byteSize();
        if (this.prefiltered != null) total += this.prefiltered.byteSize();
        if (this.irradiance != null) total += this.irradiance.byteSize();
        return total;
    }

    /**
     * Frees the raw capture cubemap, keeping the two convolved maps the shader actually reads.
     * Worth doing after a BAKE_ONCE probe settles: the radiance map is the largest of the
     * three and is only an input to the convolutions.
     */
    public void releaseIntermediates() {
        if (this.radiance != null && this.ready && this.mode == Mode.BAKE_ONCE) {
            this.radiance.close();
            this.radiance = null;
        }
    }

    public void releaseTextures() {
        if (this.radiance != null) {
            this.radiance.close();
            this.radiance = null;
        }
        if (this.prefiltered != null) {
            this.prefiltered.close();
            this.prefiltered = null;
        }
        if (this.irradiance != null) {
            this.irradiance.close();
            this.irradiance = null;
        }
        this.ready = false;
    }

    @Override
    public String toString() {
        return "ReflectionProbe[#" + this.id + " @ " + this.center + " res=" + this.resolution
                + " " + this.mode + (this.ready ? " ready" : " baking face " + this.pendingFace) + "]";
    }
}
