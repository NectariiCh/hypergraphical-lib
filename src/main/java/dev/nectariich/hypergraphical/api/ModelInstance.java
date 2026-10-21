package dev.nectariich.hypergraphical.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import dev.nectariich.hypergraphical.HypergraphicalClient;
import dev.nectariich.hypergraphical.mesh.GpuMesh;
import dev.nectariich.hypergraphical.mesh.MeshBounds;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    /** Materials per element, part then {@code usemtl} name; see {@link #setElementMaterial}. */
    @Nullable
    private Map<String, Map<String, ResourceLocation>> elementMaterials;

    private double x;
    private double y;
    private double z;

    private final Quaternionf rotation = new Quaternionf();
    private final Vector3f scale = new Vector3f(1.0F, 1.0F, 1.0F);

    private boolean visible = true;
    private boolean visibleInProbes = true;

    /** Parts drawn, or {@code null} for all of them; see {@link #showOnlyParts}. */
    @Nullable
    private Set<String> shownParts;
    private final Set<String> hiddenParts = new HashSet<>();
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

    /** Exponent on the mesh's baked occlusion; see {@link #setOcclusionStrength}. */
    private float occlusionStrength = 1.0F;

    /** Glow per part; see {@link #setPartGlow}. {@code null} until something glows. */
    @Nullable
    private Map<String, Float> partGlow;
    /** Glow per material element, part then material; see {@link #setElementGlow}. */
    @Nullable
    private Map<String, Map<String, Float>> elementGlow;

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

    /**
     * Overrides the material for one material element: the faces of {@code part} drawn with
     * the {@code usemtl} material {@code material} (see {@link MeshInfo#elements()}). Wins over
     * {@link #setSubmeshMaterial} for that element. {@code null} removes the override.
     */
    public ModelInstance setElementMaterial(String part, String material,
                                            @Nullable ResourceLocation override) {
        if (override == null) {
            if (this.elementMaterials != null) {
                Map<String, ResourceLocation> materials = this.elementMaterials.get(part);
                if (materials != null) {
                    materials.remove(material);
                    if (materials.isEmpty()) {
                        this.elementMaterials.remove(part);
                    }
                }
            }
            return this;
        }
        if (this.elementMaterials == null) {
            this.elementMaterials = new java.util.HashMap<>(4);
        }
        this.elementMaterials.computeIfAbsent(part, k -> new java.util.HashMap<>(4))
                .put(material, override);
        return this;
    }

    /** Removes every {@link #setElementMaterial} override. */
    public ModelInstance clearElementMaterials() {
        this.elementMaterials = null;
        return this;
    }

    /**
     * Resolves the material for one sub-mesh by its part and {@code usemtl} name: an element
     * override, then a sub-mesh override, then the instance material.
     */
    public ResourceLocation materialFor(String part, @Nullable String submesh) {
        if (submesh != null && this.elementMaterials != null) {
            Map<String, ResourceLocation> materials = this.elementMaterials.get(part);
            if (materials != null) {
                ResourceLocation override = materials.get(submesh);
                if (override != null) {
                    return override;
                }
            }
        }
        return materialFor(submesh);
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

    // --------------------------------------------------------------------- parts
    /**
     * Draws only the named parts. Parts are the {@code o} objects of the {@code .obj} (or its
     * {@code g} groups, when it names no objects), so one model can be split across several
     * instances, e.g. a body and a separately transformed handle.
     */
    public ModelInstance showOnlyParts(Collection<String> parts) {
        this.shownParts = new HashSet<>(parts);
        this.worldBounds = null;
        return this;
    }

    /** Hides the named parts, on top of any {@link #showOnlyParts} filter. */
    public ModelInstance hideParts(Collection<String> parts) {
        this.hiddenParts.clear();
        this.hiddenParts.addAll(parts);
        this.worldBounds = null;
        return this;
    }

    public ModelInstance showAllParts() {
        this.shownParts = null;
        this.hiddenParts.clear();
        this.worldBounds = null;
        return this;
    }

    public boolean isPartVisible(String part) {
        return (this.shownParts == null || this.shownParts.contains(part))
                && !this.hiddenParts.contains(part);
    }

    /**
     * Model-space bounds of the parts this instance draws, or {@code null} until the mesh is
     * resident (or when every part is hidden).
     */
    @Nullable
    public MeshBounds localBounds() {
        GpuMesh mesh = HypergraphicalClient.meshes().get(this.mesh);
        if (mesh == null) {
            return null;
        }
        MeshBounds out = null;
        for (Map.Entry<String, MeshBounds> part : mesh.partBounds().entrySet()) {
            if (isPartVisible(part.getKey())) {
                out = out == null ? part.getValue() : out.union(part.getValue());
            }
        }
        return out;
    }

    /**
     * World-space box around what this instance draws: its visible parts' bounds under the
     * full rotation, scale and position. Tighter than {@link #worldBounds}, which is a
     * rotation-invariant sphere for culling. {@code null} until the mesh is resident.
     */
    @Nullable
    public AABB hitbox() {
        MeshBounds local = localBounds();
        return local == null ? null : local.transform(localMatrix(), this.x, this.y, this.z);
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

    // ------------------------------------------------------------------ occlusion
    public float occlusionStrength() {
        return this.occlusionStrength;
    }

    /**
     * How strongly this instance shows its mesh's baked ambient occlusion (see
     * {@link HypergraphicalAPI#setOcclusionBaking}). Applied in the vertex shader as
     * {@code pow(occlusion, strength)}: 0 turns occlusion off, 1 shows the baked value as is,
     * and values up to 2 darken it further (a half-occluded 0.5 becomes 0.25 at 2). Clamped
     * to 0..2. Default 1. Travels in the instance stream, so it never splits a batch. Has no
     * visible effect on a mesh that is not baked, whose occlusion is 1 everywhere.
     */
    public ModelInstance setOcclusionStrength(float strength) {
        this.occlusionStrength = Float.isFinite(strength)
                ? Math.max(0.0F, Math.min(2.0F, strength)) : 1.0F;
        return this;
    }

    // ----------------------------------------------------------------------- glow
    /**
     * Makes every sub-mesh of one part glow, for an indicator lamp or a lit lens.
     *
     * <p>Glow adds the surface's own albedo colour (texture times material tint times instance
     * tint, in linear space) times {@code strength} as emission, lifts the vanilla lightmap
     * term to full block light, and lifts the baked occlusion, so the part reads as lit even
     * in a dark room. {@code strength} 1 is roughly "as bright as its colour"; values above 1
     * saturate towards white. 0 or less removes the glow. Clamped to 16.
     *
     * <p>Glow is per sub-mesh in the instance stream, so changing it every frame is free and
     * never splits a batch. A part that is hidden on this instance simply does not draw.
     */
    public ModelInstance setPartGlow(String part, float strength) {
        if (!(strength > 0.0F)) {
            if (this.partGlow != null) {
                this.partGlow.remove(part);
            }
            return this;
        }
        if (this.partGlow == null) {
            this.partGlow = new java.util.HashMap<>(4);
        }
        this.partGlow.put(part, Math.min(16.0F, strength));
        return this;
    }

    /**
     * Makes one material element glow: the faces of {@code part} drawn with the
     * {@code usemtl} material {@code material} (see {@link MeshInfo#elements()}). Same
     * meaning and range as {@link #setPartGlow}. When both a part glow and an element glow
     * apply to a sub-mesh, the stronger one wins.
     */
    public ModelInstance setElementGlow(String part, String material, float strength) {
        if (!(strength > 0.0F)) {
            if (this.elementGlow != null) {
                Map<String, Float> materials = this.elementGlow.get(part);
                if (materials != null) {
                    materials.remove(material);
                    if (materials.isEmpty()) {
                        this.elementGlow.remove(part);
                    }
                }
            }
            return this;
        }
        if (this.elementGlow == null) {
            this.elementGlow = new java.util.HashMap<>(4);
        }
        this.elementGlow.computeIfAbsent(part, k -> new java.util.HashMap<>(4))
                .put(material, Math.min(16.0F, strength));
        return this;
    }

    /** Removes every part and element glow. */
    public ModelInstance clearGlow() {
        this.partGlow = null;
        this.elementGlow = null;
        return this;
    }

    /**
     * The glow for one sub-mesh, identified by its part and material name: the stronger of
     * its part glow and its element glow, 0 when neither is set.
     */
    public float glowFor(String part, String material) {
        float glow = 0.0F;
        if (this.partGlow != null) {
            Float value = this.partGlow.get(part);
            if (value != null) {
                glow = value;
            }
        }
        if (this.elementGlow != null) {
            Map<String, Float> materials = this.elementGlow.get(part);
            if (materials != null) {
                Float value = materials.get(material);
                if (value != null) {
                    glow = Math.max(glow, value);
                }
            }
        }
        return glow;
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
