package dev.nectariich.hypergraphical.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;

/**
 * One renderable placement of a mesh in the world &mdash; the equivalent of a Unity
 * {@code MeshRenderer} on a transform, not a block.
 *
 * <p>Instances live at arbitrary {@code double} coordinates with a full quaternion rotation
 * and non-uniform scale. They are not tied to the block grid, do not participate in
 * collision, occlusion culling by chunk, or world saving; they are a pure client-side
 * scene graph. Nothing stops you from placing a million of them, though
 * {@code render.maxInstancesPerFrame} will stop you from drawing a million.
 *
 * <p>Mutate freely from the client thread. Setters mark the instance dirty, which is what
 * lets static instances skip matrix and lightmap recomputation entirely &mdash; the single
 * biggest win when most of a scene is not moving.
 *
 * <p><b>Per-instance material overrides.</b> {@link #metallic}, {@link #smoothness} and
 * {@link #emissiveStrength} default to {@link Float#NaN}, meaning "inherit from the
 * material". Set them to override without cloning the material, the way a Unity
 * {@code MaterialPropertyBlock} does &mdash; overridden values do not split the instancing
 * batch, because they travel in the per-instance vertex stream.
 */
public final class ModelInstance {

    /** Sentinel for "inherit this value from the material". */
    public static final float INHERIT = Float.NaN;

    /**
     * Value of {@link #lightSampleTick()} meaning world light has never been sampled.
     *
     * <p>Compare against this explicitly. {@code currentTick - LIGHT_NEVER_SAMPLED} overflows
     * to a negative number, so folding it into a staleness subtraction silently reports every
     * fresh instance as up to date and leaves it at light level zero forever.
     */
    public static final int LIGHT_NEVER_SAMPLED = Integer.MIN_VALUE;

    private static int nextId = 1;

    private final int id;

    private ResourceLocation mesh;
    private ResourceLocation material;
    @Nullable
    private Map<String, ResourceLocation> submeshMaterials;

    private double x;
    private double y;
    private double z;

    private final Quaternionf rotation = new Quaternionf();
    private final Vector3f scale = new Vector3f(1.0F, 1.0F, 1.0F);

    private boolean visible = true;
    private boolean visibleInProbes = true;
    private boolean removed;

    /** Packed vanilla lightmap coordinate, or -1 to sample the world at this position. */
    private int packedLight = -1;
    private int lightSampleTick = LIGHT_NEVER_SAMPLED;
    private int sampledLight;

    private final Vector4f tint = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private final Vector2f uvOffset = new Vector2f();

    private float metallic = INHERIT;
    private float smoothness = INHERIT;
    private float emissiveStrength = INHERIT;

    /**
     * Rotation and scale baked into a matrix with zero translation. Recomputed only when the
     * transform changes; the renderer supplies camera-relative translation each frame, which
     * is what keeps us free of float precision artefacts far from the origin.
     */
    private final Matrix4f localMatrix = new Matrix4f();
    private boolean transformDirty = true;

    /** World AABB used for frustum and distance culling; -1 radius means "not yet known". */
    private double boundsRadius = -1.0D;
    @Nullable
    private AABB worldBounds;

    /** Probe indices resolved by the probe manager each frame. */
    private int probeA = -1;
    private int probeB = -1;
    private float probeBlend;

    public ModelInstance(ResourceLocation mesh, ResourceLocation material, double x, double y, double z) {
        this.id = nextId++;
        this.mesh = mesh;
        this.material = material;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    // ------------------------------------------------------------------ identity
    public int id() {
        return this.id;
    }

    public ResourceLocation mesh() {
        return this.mesh;
    }

    public ModelInstance setMesh(ResourceLocation mesh) {
        this.mesh = mesh;
        this.boundsRadius = -1.0D;
        this.worldBounds = null;
        return this;
    }

    public ResourceLocation material() {
        return this.material;
    }

    public ModelInstance setMaterial(ResourceLocation material) {
        this.material = material;
        return this;
    }

    /**
     * Overrides the material for one named sub-mesh. Names come from the {@code .obj}
     * {@code usemtl} statements, so a multi-material export stays addressable.
     */
    public ModelInstance setSubmeshMaterial(String submesh, ResourceLocation material) {
        if (this.submeshMaterials == null) {
            this.submeshMaterials = new java.util.HashMap<>(4);
        }
        this.submeshMaterials.put(submesh, material);
        return this;
    }

    public Map<String, ResourceLocation> submeshMaterials() {
        return this.submeshMaterials == null ? Collections.emptyMap() : this.submeshMaterials;
    }

    /** Resolves the material for a named sub-mesh, falling back to the instance material. */
    public ResourceLocation materialFor(@Nullable String submesh) {
        if (submesh != null && this.submeshMaterials != null) {
            ResourceLocation override = this.submeshMaterials.get(submesh);
            if (override != null) {
                return override;
            }
        }
        return this.material;
    }

    // ----------------------------------------------------------------- transform
    public double x() {
        return this.x;
    }

    public double y() {
        return this.y;
    }

    public double z() {
        return this.z;
    }

    public Vec3 position() {
        return new Vec3(this.x, this.y, this.z);
    }

    public ModelInstance setPosition(double x, double y, double z) {
        if (x != this.x || y != this.y || z != this.z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.worldBounds = null;
            this.lightSampleTick = LIGHT_NEVER_SAMPLED;
        }
        return this;
    }

    public ModelInstance setPosition(Vec3 pos) {
        return setPosition(pos.x, pos.y, pos.z);
    }

    public ModelInstance move(double dx, double dy, double dz) {
        return setPosition(this.x + dx, this.y + dy, this.z + dz);
    }

    /** Live rotation quaternion. Call {@link #markTransformDirty()} after editing in place. */
    public Quaternionf rotation() {
        return this.rotation;
    }

    public ModelInstance setRotation(Quaternionf q) {
        this.rotation.set(q);
        return markTransformDirty();
    }

    /** Euler rotation in degrees, applied Y-X-Z the way Unity's inspector does. */
    public ModelInstance setEulerDegrees(float yaw, float pitch, float roll) {
        this.rotation.identity()
                .rotateY((float) Math.toRadians(yaw))
                .rotateX((float) Math.toRadians(pitch))
                .rotateZ((float) Math.toRadians(roll));
        return markTransformDirty();
    }

    /** Live scale vector. Call {@link #markTransformDirty()} after editing in place. */
    public Vector3f scale() {
        return this.scale;
    }

    public ModelInstance setScale(float uniform) {
        return setScale(uniform, uniform, uniform);
    }

    public ModelInstance setScale(float sx, float sy, float sz) {
        this.scale.set(sx, sy, sz);
        return markTransformDirty();
    }

    public ModelInstance markTransformDirty() {
        this.transformDirty = true;
        this.worldBounds = null;
        return this;
    }

    /**
     * Rotation/scale matrix with zero translation, recomputed lazily. The renderer fills in
     * the camera-relative translation column itself.
     */
    public Matrix4f localMatrix() {
        if (this.transformDirty) {
            this.localMatrix.identity().rotate(this.rotation).scale(this.scale);
            this.transformDirty = false;
        }
        return this.localMatrix;
    }

    // ------------------------------------------------------------------- culling
    /**
     * Caches the mesh's local bounding sphere radius so world bounds can be derived without
     * touching the mesh again. Called by the renderer once the mesh is resident.
     */
    public void setLocalBoundsRadius(double radius) {
        if (this.boundsRadius != radius) {
            this.boundsRadius = radius;
            this.worldBounds = null;
        }
    }

    public boolean hasBounds() {
        return this.boundsRadius >= 0.0D;
    }

    /**
     * World-space AABB, from the local bounding sphere scaled by the largest axis scale. A
     * sphere-derived box is looser than a tight OBB but it is rotation-invariant, so it never
     * needs recomputing when only the rotation changes.
     */
    public AABB worldBounds() {
        AABB cached = this.worldBounds;
        if (cached == null) {
            double maxScale = Math.max(Math.abs(this.scale.x),
                    Math.max(Math.abs(this.scale.y), Math.abs(this.scale.z)));
            double r = Math.max(this.boundsRadius, 0.0D) * maxScale;
            cached = new AABB(this.x - r, this.y - r, this.z - r, this.x + r, this.y + r, this.z + r);
            this.worldBounds = cached;
        }
        return cached;
    }

    // ------------------------------------------------------------------ shading
    public boolean visible() {
        return this.visible && !this.removed;
    }

    public ModelInstance setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    /** Whether this instance is drawn into reflection probe captures. */
    public boolean visibleInProbes() {
        return this.visibleInProbes;
    }

    public ModelInstance setVisibleInProbes(boolean visibleInProbes) {
        this.visibleInProbes = visibleInProbes;
        return this;
    }

    public ModelInstance setPackedLight(int packedLight) {
        this.packedLight = packedLight;
        return this;
    }

    /** Restores automatic world light sampling. */
    public ModelInstance setAutoLight() {
        this.packedLight = -1;
        this.lightSampleTick = LIGHT_NEVER_SAMPLED;
        return this;
    }

    public int rawPackedLight() {
        return this.packedLight;
    }

    /** Cached light plus the tick it was sampled on, so sampling can be throttled. */
    public int cachedLight() {
        return this.sampledLight;
    }

    public int lightSampleTick() {
        return this.lightSampleTick;
    }

    public void cacheLight(int packed, int tick) {
        this.sampledLight = packed;
        this.lightSampleTick = tick;
    }

    public Vector4f tint() {
        return this.tint;
    }

    public ModelInstance setTint(float r, float g, float b, float a) {
        this.tint.set(r, g, b, a);
        return this;
    }

    /** Additional UV translation on top of the material offset. Animate this for scrolling. */
    public Vector2f uvOffset() {
        return this.uvOffset;
    }

    public ModelInstance setUvOffset(float u, float v) {
        this.uvOffset.set(u, v);
        return this;
    }

    public float metallic() {
        return this.metallic;
    }

    public ModelInstance setMetallic(float metallic) {
        this.metallic = metallic;
        return this;
    }

    public float smoothness() {
        return this.smoothness;
    }

    public ModelInstance setSmoothness(float smoothness) {
        this.smoothness = smoothness;
        return this;
    }

    public float emissiveStrength() {
        return this.emissiveStrength;
    }

    public ModelInstance setEmissiveStrength(float emissiveStrength) {
        this.emissiveStrength = emissiveStrength;
        return this;
    }

    // -------------------------------------------------------------------- probes
    public void setProbes(int probeA, int probeB, float blend) {
        this.probeA = probeA;
        this.probeB = probeB;
        this.probeBlend = blend;
    }

    public int probeA() {
        return this.probeA;
    }

    public int probeB() {
        return this.probeB;
    }

    public float probeBlend() {
        return this.probeBlend;
    }

    // ----------------------------------------------------------------- lifecycle
    public boolean removed() {
        return this.removed;
    }

    /** Marks the instance for removal. The scene drops it on the next sweep. */
    public void markRemoved() {
        this.removed = true;
    }

    @Override
    public String toString() {
        return "ModelInstance[#" + this.id + " " + this.mesh + " @ "
                + String.format("%.2f %.2f %.2f", this.x, this.y, this.z) + "]";
    }
}
