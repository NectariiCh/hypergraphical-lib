package dev.nectariich.hypergraphical.mesh;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * CPU-side geometry, immediately before upload.
 *
 * <p>Vertices are interleaved into a single {@code float[]} in the layout below. Everything is
 * full 32-bit float on purpose: Minecraft's own {@code BakedQuad} format packs normals into
 * three signed bytes and has no tangent at all, which is fine for the vanilla diffuse-only
 * shader and visibly wrong the moment you put a smooth metal in front of a reflection probe.
 * 8-bit normals quantise a mirror highlight into faceted bands.
 *
 * <pre>
 *   offset (floats)   attribute
 *   0                 position   vec3   model space
 *   3                 normal     vec3   unit
 *   6                 uv         vec2
 *   8                 tangent    vec4   xyz unit, w = handedness (+/-1)
 * </pre>
 */
public final class MeshData {

    /** Floats per vertex. */
    public static final int STRIDE_FLOATS = 12;

    /** Bytes per vertex. */
    public static final int STRIDE_BYTES = STRIDE_FLOATS * Float.BYTES;

    public static final int OFF_POSITION = 0;
    public static final int OFF_NORMAL = 3;
    public static final int OFF_UV = 6;
    public static final int OFF_TANGENT = 8;

    /**
     * One contiguous index range sharing a material and a part. {@code name} is the
     * {@code usemtl} that produced it, so a multi-material export stays addressable from code;
     * {@code part} is the {@code o} (or, failing that, {@code g}) object it belongs to, so the
     * pieces of a model can be told apart and drawn on their own.
     */
    public record SubMesh(String name,
                          int indexOffset,
                          int indexCount,
                          @Nullable ResourceLocation autoMaterial,
                          String part) {}

    private final float[] vertices;
    private final int[] indices;
    private final List<SubMesh> subMeshes;
    private final float[] boundsMin;
    private final float[] boundsMax;
    private final double boundingRadius;
    private final Map<String, MeshBounds> partBounds;

    public MeshData(float[] vertices, int[] indices, List<SubMesh> subMeshes,
                    float[] boundsMin, float[] boundsMax) {
        this.vertices = vertices;
        this.indices = indices;
        this.subMeshes = List.copyOf(subMeshes);
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
        this.partBounds = MeshBounds.ofParts(vertices, indices, this.subMeshes);

        // Radius about the model ORIGIN, not the bounds centre: instances rotate about their
        // origin, so an origin-centred sphere is the one bound that stays valid under any
        // rotation and never needs recomputing when only the rotation changes.
        //
        // Measured from the vertices rather than from the AABB corners. Taking the furthest
        // AABB corner would be correct but slack by up to sqrt(3): a unit sphere would get a
        // 0.866 radius instead of 0.5, and every instance would survive frustum culling for
        // 73% longer than it should.
        double radiusSq = 0.0D;
        for (int v = 0; v + STRIDE_FLOATS <= vertices.length; v += STRIDE_FLOATS) {
            double x = vertices[v];
            double y = vertices[v + 1];
            double z = vertices[v + 2];
            radiusSq = Math.max(radiusSq, x * x + y * y + z * z);
        }
        this.boundingRadius = Math.sqrt(radiusSq);
    }

    public float[] vertices() {
        return this.vertices;
    }

    public int[] indices() {
        return this.indices;
    }

    public List<SubMesh> subMeshes() {
        return this.subMeshes;
    }

    public int vertexCount() {
        return this.vertices.length / STRIDE_FLOATS;
    }

    public int triangleCount() {
        return this.indices.length / 3;
    }

    public float[] boundsMin() {
        return this.boundsMin;
    }

    public float[] boundsMax() {
        return this.boundsMax;
    }

    public MeshBounds bounds() {
        return MeshBounds.of(this.boundsMin, this.boundsMax);
    }

    /** Bounds of each part, keyed by part name, in the order the parts first appear. */
    public Map<String, MeshBounds> partBounds() {
        return this.partBounds;
    }

    /** Radius of a sphere centred on the model origin that contains the whole mesh. */
    public double boundingRadius() {
        return this.boundingRadius;
    }
}
