package dev.nectariich.hypergraphical.reflect;

import dev.nectariich.hypergraphical.HgConfig;
import dev.nectariich.hypergraphical.Hypergraphical;
import dev.nectariich.hypergraphical.api.ModelInstance;
import dev.nectariich.hypergraphical.scene.HgScene;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Owns every {@link ReflectionProbe}: capture scheduling, GPU budget, and deciding which
 * probes light which instance.
 *
 * <h2>Scheduling</h2>
 *
 * <p>At most {@code probes.facesPerFrame} faces are captured per frame, from the nearest probe
 * that still wants work. A full bake is therefore six frames of extra level rendering, spread
 * out, and then nothing at all for a {@code BAKE_ONCE} probe. That is the entire reason real
 * cubemap capture is affordable here: the expensive thing happens rarely, not every frame.
 *
 * <h2>Budget and eviction</h2>
 *
 * <p>Only the {@code probes.maxActive} probes nearest the camera keep their cubemaps. Others
 * are released and re-baked on approach. At the default 128px that is roughly 2&nbsp;MB of
 * VRAM per probe, so the cap is what stops a scene with two hundred probes from being a
 * memory leak with extra steps.
 *
 * <h2>Assignment</h2>
 *
 * <p>Each instance gets up to two probes and a blend weight, preferring probes whose box
 * contains it and falling back to nearest by centre distance. Two rather than one because
 * snapping between probes as an object crosses a boundary is very visible on a smooth
 * surface; two rather than many because GL 3.3 has no cubemap arrays, so each extra probe is
 * another sampler and another batch key.
 */
public final class ProbeManager implements AutoCloseable {

    /** Added to a probe's rank when an instance is outside its box. */
    private static final double CONTAINMENT_PENALTY = 1.0E9D;

    /** Ticks an auto-placed probe survives after its cell empties. */
    private static final int AUTO_RETIRE_GRACE_TICKS = 60;

    private final List<ReflectionProbe> probes = new ObjectArrayList<>();

    /** Automatically placed probes, keyed by grid cell. A subset of {@link #probes}. */
    private final Long2ObjectOpenHashMap<ReflectionProbe> autoProbes = new Long2ObjectOpenHashMap<>();

    // Reused per frame so auto-placement allocates nothing in steady state.
    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    private final LongArrayList pendingCells = new LongArrayList();
    private final Long2IntOpenHashMap autoProbeLastSeen = new Long2IntOpenHashMap();
    private final ProbeCapture capture = new ProbeCapture();
    private final IblProcessor ibl = new IblProcessor();

    private int tick;
    private int facesCapturedThisFrame;

    // ------------------------------------------------------------------ lifecycle
    public void reload(ResourceManager resources) {
        this.capture.reload(resources);
        this.ibl.reload(resources);
        // Shaders changed, so every baked result is stale.
        for (ReflectionProbe probe : this.probes) {
            probe.invalidate();
        }
    }

    public ProbeCapture capture() {
        return this.capture;
    }

    public IblProcessor ibl() {
        return this.ibl;
    }

    public boolean capturing() {
        return this.capture.capturing();
    }

    // ------------------------------------------------------------------- registry
    public ReflectionProbe add(Vec3 center, Vec3 halfExtents, int resolution,
                               ReflectionProbe.Mode mode) {
        ReflectionProbe probe = new ReflectionProbe(center, halfExtents,
                HgConfig.floorPow2(resolution), mode);
        this.probes.add(probe);
        Hypergraphical.LOGGER.debug("[hypergraphical] Added {}", probe);
        return probe;
    }

    /** A probe with the configured default resolution and a box sized from its radius. */
    public ReflectionProbe add(Vec3 center, double radius) {
        return add(center, new Vec3(radius, radius, radius), HgConfig.PROBE_RESOLUTION.get(),
                ReflectionProbe.Mode.BAKE_ONCE);
    }

    public void remove(ReflectionProbe probe) {
        if (this.probes.remove(probe)) {
            probe.releaseTextures();
            this.autoProbes.values().remove(probe);
        }
    }

    public void clear() {
        for (ReflectionProbe probe : this.probes) {
            probe.releaseTextures();
        }
        this.probes.clear();
        this.autoProbes.clear();
        this.autoProbeLastSeen.clear();
    }

    public List<ReflectionProbe> probes() {
        return this.probes;
    }

    public int count() {
        return this.probes.size();
    }

    public int readyCount() {
        int ready = 0;
        for (ReflectionProbe probe : this.probes) {
            if (probe.ready()) {
                ready++;
            }
        }
        return ready;
    }

    public long vramBytes() {
        long total = 0L;
        for (ReflectionProbe probe : this.probes) {
            total += probe.vramBytes();
        }
        return total;
    }

    /** Forces every probe to re-bake. */
    public void invalidateAll() {
        for (ReflectionProbe probe : this.probes) {
            probe.invalidate();
        }
    }

    public void tick() {
        this.tick++;
    }

    // -------------------------------------------------------------- auto placement
    /** Running centroid and extent of the instances occupying one grid cell. */
    private static final class Cell {
        double sumX;
        double sumY;
        double sumZ;
        int count;
        double maxRadius;

        void add(ModelInstance instance) {
            this.sumX += instance.x();
            this.sumY += instance.y();
            this.sumZ += instance.z();
            this.count++;
            this.maxRadius = Math.max(this.maxRadius,
                    instance.hasBounds() ? instance.worldBounds().getXsize() * 0.5D : 1.0D);
        }

        Vec3 centroid() {
            return new Vec3(this.sumX / this.count, this.sumY / this.count, this.sumZ / this.count);
        }
    }

    /**
     * Creates and retires probes so that every instance in range has one, with no manual
     * placement.
     *
     * <p>Probes are placed per <b>grid cell</b> rather than per instance. One probe per
     * instance would be the accurate choice and the wrong one: each probe costs six extra
     * level renders to bake and a couple of megabytes of VRAM, so a hundred models would mean
     * a hundred bakes. A cell shares one probe between everything inside it, which is also
     * roughly how you would place them by hand &mdash; one per room, not one per object &mdash;
     * and box projection is what keeps a shared probe looking right away from its centre.
     *
     * <p>Each probe sits at the centroid of its cell's instances, lifted clear of them. Not at
     * the geometric cell centre: a cell centre lands inside terrain often enough to matter,
     * and a probe buried in stone captures six faces of solid block.
     */
    public void updateAutoProbes(HgScene scene, Vec3 cameraPos) {
        if (!HgConfig.PROBE_AUTO.get()) {
            if (!this.autoProbes.isEmpty()) {
                clearAutoProbes();
            }
            return;
        }

        final int cellSize = HgConfig.PROBE_AUTO_CELL.get();
        final double maxDistance = HgConfig.MAX_RENDER_DISTANCE.get();
        final double maxDistanceSq = maxDistance * maxDistance;

        this.cells.clear();
        for (ModelInstance instance : scene.all()) {
            if (!instance.visible()) {
                continue;
            }
            double dx = instance.x() - cameraPos.x;
            double dy = instance.y() - cameraPos.y;
            double dz = instance.z() - cameraPos.z;
            if (dx * dx + dy * dy + dz * dz > maxDistanceSq) {
                continue;
            }
            long key = cellKey(instance.x(), instance.y(), instance.z(), cellSize);
            this.cells.computeIfAbsent(key, k -> new Cell()).add(instance);
        }

        for (long key : this.cells.keySet()) {
            this.autoProbeLastSeen.put(key, this.tick);
        }

        // Retire probes whose cell has been empty for a while.
        this.autoProbes.long2ObjectEntrySet().removeIf(entry -> {
            long key = entry.getLongKey();
            if (this.cells.containsKey(key)) {
                return false;
            }
            // The grace period is what makes a moving instance usable. Retiring the moment a
            // cell empties means an object walking across a cell boundary throws away its
            // probe and waits six frames for a fresh bake on the other side - losing its
            // reflection every time it crosses one of an invisible grid of lines.
            if (this.autoProbeLastSeen.containsKey(key)
                    && this.tick - this.autoProbeLastSeen.get(key) < AUTO_RETIRE_GRACE_TICKS) {
                return false;
            }
            ReflectionProbe probe = entry.getValue();
            probe.releaseTextures();
            this.probes.remove(probe);
            this.autoProbeLastSeen.remove(key);
            return true;
        });

        int budget = HgConfig.PROBE_AUTO_MAX.get() - this.autoProbes.size();
        if (budget <= 0) {
            return;
        }

        // New cells nearest the camera first, so walking into a scene lights up what is in
        // front of you rather than whichever cell happened to hash first.
        this.pendingCells.clear();
        for (long key : this.cells.keySet()) {
            if (!this.autoProbes.containsKey(key)) {
                this.pendingCells.add(key);
            }
        }
        this.pendingCells.sort((a, b) -> Double.compare(
                this.cells.get(a).centroid().distanceToSqr(cameraPos),
                this.cells.get(b).centroid().distanceToSqr(cameraPos)));

        int created = 0;
        for (long key : this.pendingCells) {
            if (created >= budget) {
                break;
            }
            Cell cell = this.cells.get(key);
            Vec3 centre = cell.centroid().add(0.0D, cell.maxRadius + 0.5D, 0.0D);

            // A box slightly larger than the cell, so every instance in the cell is contained
            // (a centroid near one edge would otherwise leave the far corner outside) and
            // neighbouring probes overlap enough for the two-probe blend to have something
            // to blend.
            double half = cellSize * 0.6D;
            ReflectionProbe probe = add(centre, new Vec3(half, half, half),
                    HgConfig.PROBE_RESOLUTION.get(), ReflectionProbe.Mode.BAKE_ONCE);
            this.autoProbes.put(key, probe);
            created++;
        }
    }

    private static long cellKey(double x, double y, double z, int cellSize) {
        long cx = Math.floorDiv((long) Math.floor(x), cellSize);
        long cy = Math.floorDiv((long) Math.floor(y), cellSize);
        long cz = Math.floorDiv((long) Math.floor(z), cellSize);
        return (cx & 0x1FFFFFL) << 42 | (cy & 0x1FFFFFL) << 21 | (cz & 0x1FFFFFL);
    }

    /** Removes every automatically placed probe, leaving manual ones alone. */
    public void clearAutoProbes() {
        for (ReflectionProbe probe : this.autoProbes.values()) {
            probe.releaseTextures();
            this.probes.remove(probe);
        }
        this.autoProbes.clear();
        this.autoProbeLastSeen.clear();
    }

    public int autoProbeCount() {
        return this.autoProbes.size();
    }

    // ------------------------------------------------------------------ capturing
    /**
     * Advances probe baking. Must be called from outside {@code renderLevel} &mdash; see
     * {@link ProbeCapture}. Returns the number of faces captured.
     */
    public int pumpCaptures(Vec3 cameraPos, float partialTick) {
        this.facesCapturedThisFrame = 0;
        if (this.probes.isEmpty()
                || HgConfig.PROBE_CAPTURE.get() == HgConfig.ProbeCapture.OFF
                || !this.ibl.ready()) {
            return 0;
        }

        // Sorts the list nearest-first and evicts anything past the budget, so the window
        // scheduling works against below is simply the first maxActive entries.
        int inBudget = enforceBudget(cameraPos);

        int budget = HgConfig.PROBE_FACES_PER_FRAME.get();
        int refreshTicks = HgConfig.PROBE_REFRESH_TICKS.get();

        while (this.facesCapturedThisFrame < budget) {
            ReflectionProbe probe = nextProbeNeedingCapture(cameraPos, refreshTicks, inBudget);
            if (probe == null) {
                break;
            }
            if (!probe.capturing()) {
                // The window can shrink below the configured resolution, and the capture path
                // reads back from the main framebuffer. Resize between probes, never mid-bake:
                // setResolution invalidates, which would restart the face counter and leave
                // the probe re-capturing face 0 forever.
                int wanted = Math.min(HgConfig.PROBE_RESOLUTION.get(),
                        this.capture.maxCaptureResolution());
                if (wanted >= 16 && wanted != probe.resolution()) {
                    probe.setResolution(wanted);
                }
                probe.beginCapture();
            }
            if (!this.capture.captureFace(probe, probe.pendingFace(), partialTick)) {
                // Capture is impossible right now (no level, no shader, no framebuffer). Stop
                // for this frame rather than spinning through every probe.
                break;
            }
            probe.faceCaptured();
            this.facesCapturedThisFrame++;

            if (probe.allFacesCaptured()) {
                this.ibl.convolve(probe);
                probe.finishCapture(this.tick);
                probe.releaseIntermediates();
                Hypergraphical.LOGGER.debug(
                        "[hypergraphical] Baked probe #{} at {} via {} capture",
                        probe.id(), probe.center(), this.capture.effectiveStrategy());
            }
        }
        return this.facesCapturedThisFrame;
    }

    /**
     * Nearest probe within the active window that wants a face captured.
     *
     * <p>Restricting the search to the first {@code inBudget} entries is what stops a scene
     * with more probes than the budget from thrashing: an out-of-budget probe is evicted by
     * {@link #enforceBudget} every frame, so scheduling one would bake it and immediately
     * throw the result away, forever.
     */
    @Nullable
    private ReflectionProbe nextProbeNeedingCapture(Vec3 cameraPos, int refreshTicks,
                                                    int inBudget) {
        ReflectionProbe best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int i = 0; i < inBudget; i++) {
            ReflectionProbe probe = this.probes.get(i);
            if (!probe.needsCapture(this.tick, refreshTicks)) {
                continue;
            }
            // Finish a probe already mid-bake before starting another, so a cubemap never
            // ends up holding faces from two different capture attempts.
            if (probe.capturing()) {
                return probe;
            }
            double distance = probe.distanceSqTo(cameraPos.x, cameraPos.y, cameraPos.z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = probe;
            }
        }
        return best;
    }

    /**
     * Sorts probes nearest-first and frees the cubemaps of any beyond the active budget.
     *
     * @return how many probes are within budget, i.e. the length of the prefix of
     *         {@link #probes} that may hold GPU memory
     */
    private int enforceBudget(Vec3 cameraPos) {
        int maxActive = HgConfig.PROBE_MAX_ACTIVE.get();
        if (this.probes.size() <= maxActive) {
            return this.probes.size();
        }
        this.probes.sort((a, b) -> Double.compare(
                a.distanceSqTo(cameraPos.x, cameraPos.y, cameraPos.z),
                b.distanceSqTo(cameraPos.x, cameraPos.y, cameraPos.z)));
        for (int i = maxActive; i < this.probes.size(); i++) {
            ReflectionProbe probe = this.probes.get(i);
            if (probe.ready() || probe.capturing()) {
                probe.releaseTextures();
                probe.invalidate();
            }
        }
        return maxActive;
    }

    // ----------------------------------------------------------------- assignment
    /**
     * Picks up to two probes for an instance and stores them on it.
     *
     * <p>Containment wins over proximity: a probe whose box the instance sits inside is the
     * one an author placed for it, even if another probe's centre happens to be closer.
     */
    public void assignProbes(ModelInstance instance) {
        ReflectionProbe first = null;
        ReflectionProbe second = null;
        // Rank includes a containment penalty; distance stays raw for the blend weight.
        double firstRank = Double.MAX_VALUE;
        double secondRank = Double.MAX_VALUE;
        double firstDistance = 0.0D;
        double secondDistance = 0.0D;

        double x = instance.x();
        double y = instance.y();
        double z = instance.z();

        for (ReflectionProbe probe : this.probes) {
            if (!probe.ready()) {
                continue;
            }
            double distance = probe.distanceSqTo(x, y, z);
            // Push non-containing probes behind every containing one without excluding them,
            // so an instance between boxes still has something to reflect.
            double rank = probe.contains(x, y, z) ? distance : distance + CONTAINMENT_PENALTY;

            if (rank < firstRank) {
                secondRank = firstRank;
                secondDistance = firstDistance;
                second = first;
                firstRank = rank;
                firstDistance = distance;
                first = probe;
            } else if (rank < secondRank) {
                secondRank = rank;
                secondDistance = distance;
                second = probe;
            }
        }

        if (first == null) {
            instance.setProbes(-1, -1, 0.0F);
            return;
        }
        if (second == null || !HgConfig.PROBE_BLEND.get()) {
            instance.setProbes(first.id(), -1, 0.0F);
            return;
        }

        // Inverse-distance weight: 0 when the instance sits on the nearer probe, approaching
        // 0.5 when the two are equidistant. Capped at 0.5 so the nearer probe always leads.
        double a = Math.sqrt(firstDistance);
        double b = Math.sqrt(secondDistance);
        float blend = (float) (a / Math.max(1.0E-4D, a + b));
        instance.setProbes(first.id(), second.id(), Math.min(0.5F, blend));
    }

    @Nullable
    public ReflectionProbe byId(int id) {
        for (ReflectionProbe probe : this.probes) {
            if (probe.id() == id) {
                return probe;
            }
        }
        return null;
    }

    public int facesCapturedLastFrame() {
        return this.facesCapturedThisFrame;
    }

    public HgConfig.ProbeCapture effectiveStrategy() {
        return this.capture.effectiveStrategy();
    }

    @Override
    public void close() {
        clear();
        this.capture.close();
        this.ibl.close();
    }
}
