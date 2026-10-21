package dev.nectariich.hypergraphical.mesh;

import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An axis-aligned box in model space, in the mesh's own units.
 *
 * <p>Kept per mesh and per part, on the CPU side and after upload alike, because a model's
 * size is what everything placing it needs first: fitting it to a slot, guessing what units it
 * was exported in, and turning it into a hitbox.
 */
public record MeshBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {

    public static final MeshBounds EMPTY = new MeshBounds(0, 0, 0, 0, 0, 0);

    public static MeshBounds of(float[] min, float[] max) {
        return new MeshBounds(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    public float sizeX() {
        return this.maxX - this.minX;
    }

    public float sizeY() {
        return this.maxY - this.minY;
    }

    public float sizeZ() {
        return this.maxZ - this.minZ;
    }

    /** Largest of the three extents. */
    public float largestSize() {
        return Math.max(sizeX(), Math.max(sizeY(), sizeZ()));
    }

    public MeshBounds union(MeshBounds other) {
        return new MeshBounds(Math.min(this.minX, other.minX), Math.min(this.minY, other.minY),
                Math.min(this.minZ, other.minZ), Math.max(this.maxX, other.maxX),
                Math.max(this.maxY, other.maxY), Math.max(this.maxZ, other.maxZ));
    }

    /** Union of the named parts' bounds, or {@code null} when none of them exists. */
    public static MeshBounds unionOf(Map<String, MeshBounds> parts, Collection<String> names) {
        MeshBounds out = null;
        for (String name : names) {
            MeshBounds bounds = parts.get(name);
            if (bounds != null) {
                out = out == null ? bounds : out.union(bounds);
            }
        }
        return out;
    }

    /**
     * The world AABB of this box under {@code matrix}, offset by {@code (x, y, z)}: all eight
     * corners transformed, then boxed. Tight for any rotation of the box itself.
     */
    public AABB transform(Matrix4f matrix, double x, double y, double z) {
        float[] xs = {this.minX, this.maxX};
        float[] ys = {this.minY, this.maxY};
        float[] zs = {this.minZ, this.maxZ};
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        Vector3f corner = new Vector3f();
        for (float cx : xs) {
            for (float cy : ys) {
                for (float cz : zs) {
                    matrix.transformPosition(corner.set(cx, cy, cz));
                    minX = Math.min(minX, corner.x);
                    minY = Math.min(minY, corner.y);
                    minZ = Math.min(minZ, corner.z);
                    maxX = Math.max(maxX, corner.x);
                    maxY = Math.max(maxY, corner.y);
                    maxZ = Math.max(maxZ, corner.z);
                }
            }
        }
        return new AABB(x + minX, y + minY, z + minZ, x + maxX, y + maxY, z + maxZ);
    }

    /**
     * Bounds of the vertices used by one index range, e.g. a single sub-mesh. {@link #EMPTY}
     * for an empty range.
     */
    public static MeshBounds ofRange(float[] vertices, int[] indices, int offset, int count) {
        if (count <= 0) {
            return EMPTY;
        }
        float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = offset, end = offset + count; i < end; i++) {
            int base = indices[i] * MeshData.STRIDE_FLOATS;
            for (int axis = 0; axis < 3; axis++) {
                float value = vertices[base + axis];
                box[axis] = Math.min(box[axis], value);
                box[axis + 3] = Math.max(box[axis + 3], value);
            }
        }
        return new MeshBounds(box[0], box[1], box[2], box[3], box[4], box[5]);
    }

    /** Bounds of each part, over the vertices its triangles actually use. */
    static Map<String, MeshBounds> ofParts(float[] vertices, int[] indices, List<MeshData.SubMesh> subMeshes) {
        Map<String, float[]> boxes = new LinkedHashMap<>();
        for (MeshData.SubMesh sub : subMeshes) {
            float[] box = boxes.computeIfAbsent(sub.part(), k -> new float[] {
                    Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                    -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE});
            for (int i = sub.indexOffset(), end = i + sub.indexCount(); i < end; i++) {
                int base = indices[i] * MeshData.STRIDE_FLOATS;
                for (int axis = 0; axis < 3; axis++) {
                    float value = vertices[base + axis];
                    box[axis] = Math.min(box[axis], value);
                    box[axis + 3] = Math.max(box[axis + 3], value);
                }
            }
        }
        Map<String, MeshBounds> out = new LinkedHashMap<>();
        boxes.forEach((part, box) -> out.put(part,
                new MeshBounds(box[0], box[1], box[2], box[3], box[4], box[5])));
        return Collections.unmodifiableMap(out);
    }
}
