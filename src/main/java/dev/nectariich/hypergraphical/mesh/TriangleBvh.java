package dev.nectariich.hypergraphical.mesh;

/**
 * A bounding volume hierarchy over a mesh's triangles, built for one query: "does this ray hit
 * anything closer than {@code tMax}?". That is all an ambient occlusion bake asks, and an
 * any-hit query can stop at the first triangle it finds, which is most of why a bake over a
 * 100k-triangle model takes seconds rather than minutes.
 *
 * <p>Everything is flat primitive arrays. A node is six floats of bounds plus two ints; a
 * triangle is nine floats, copied out of the interleaved vertex buffer in BVH leaf order so a
 * leaf's triangles sit next to each other in memory. Building with object nodes instead costs
 * several times the memory and, worse, scatters the traversal across the heap.
 *
 * <p>The split is a median split on the longest axis of the node's triangle centroids. It is
 * not as tight as a surface-area heuristic, but it is O(n log n), never degenerates, and for
 * the dense, evenly tessellated meshes an occlusion bake sees the difference is small.
 *
 * <p>Immutable once built and safe to query from many threads at once; each caller passes its
 * own traversal stack.
 */
public final class TriangleBvh {

    /** Triangles per leaf. Small leaves mean more nodes but fewer wasted triangle tests. */
    private static final int LEAF_SIZE = 4;

    /** Deepest traversal stack a query may need; median splits keep depth near log2(n). */
    public static final int STACK_SIZE = 96;

    /** Per node: minX, minY, minZ, maxX, maxY, maxZ. */
    private final float[] nodeBounds;
    /** Per node: first triangle for a leaf, or the left child index for an inner node. */
    private final int[] nodeStart;
    /** Per node: triangle count for a leaf, 0 for an inner node (right child = left + 1). */
    private final int[] nodeCount;
    private final int nodeTotal;

    /** Per triangle, in leaf order: v0, v1, v2 as nine floats. */
    private final float[] triangles;

    private TriangleBvh(float[] nodeBounds, int[] nodeStart, int[] nodeCount, int nodeTotal,
                        float[] triangles) {
        this.nodeBounds = nodeBounds;
        this.nodeStart = nodeStart;
        this.nodeCount = nodeCount;
        this.nodeTotal = nodeTotal;
        this.triangles = triangles;
    }

    public int nodeCount() {
        return this.nodeTotal;
    }

    public int triangleCount() {
        return this.triangles.length / 9;
    }

    /**
     * Builds over every triangle of an interleaved vertex buffer.
     *
     * @param vertices interleaved vertices in {@link MeshData}'s layout
     * @param stride   floats per vertex
     * @param indices  triangle list
     */
    public static TriangleBvh build(float[] vertices, int stride, int[] indices) {
        int count = indices.length / 3;
        float[] centroids = new float[count * 3];
        float[] source = new float[count * 9];
        for (int t = 0; t < count; t++) {
            for (int corner = 0; corner < 3; corner++) {
                int base = indices[t * 3 + corner] * stride;
                for (int axis = 0; axis < 3; axis++) {
                    float value = vertices[base + axis];
                    source[t * 9 + corner * 3 + axis] = value;
                    centroids[t * 3 + axis] += value / 3.0F;
                }
            }
        }

        int[] order = new int[count];
        for (int t = 0; t < count; t++) {
            order[t] = t;
        }

        // A binary tree with at most LEAF_SIZE triangles per leaf and at least one per leaf
        // has fewer than 2n nodes; 2n + 1 also covers the empty and single-triangle cases.
        int capacity = Math.max(1, 2 * count + 1);
        Builder builder = new Builder(source, centroids, order, capacity);
        if (count > 0) {
            builder.build(0, count, builder.allocate());
        } else {
            builder.allocate();
        }

        float[] triangles = new float[count * 9];
        for (int i = 0; i < count; i++) {
            System.arraycopy(source, order[i] * 9, triangles, i * 9, 9);
        }
        return new TriangleBvh(builder.bounds, builder.start, builder.counts, builder.used,
                triangles);
    }

    private static final class Builder {
        final float[] source;
        final float[] centroids;
        final int[] order;
        final float[] bounds;
        final int[] start;
        final int[] counts;
        int used;

        Builder(float[] source, float[] centroids, int[] order, int capacity) {
            this.source = source;
            this.centroids = centroids;
            this.order = order;
            this.bounds = new float[capacity * 6];
            this.start = new int[capacity];
            this.counts = new int[capacity];
        }

        int allocate() {
            return this.used++;
        }

        void build(int from, int to, int node) {
            // Node bounds over the triangles' vertices, plus centroid bounds for the split.
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            float cMinX = Float.MAX_VALUE, cMinY = Float.MAX_VALUE, cMinZ = Float.MAX_VALUE;
            float cMaxX = -Float.MAX_VALUE, cMaxY = -Float.MAX_VALUE, cMaxZ = -Float.MAX_VALUE;
            for (int i = from; i < to; i++) {
                int t = this.order[i];
                for (int corner = 0; corner < 3; corner++) {
                    int base = t * 9 + corner * 3;
                    minX = Math.min(minX, this.source[base]);
                    minY = Math.min(minY, this.source[base + 1]);
                    minZ = Math.min(minZ, this.source[base + 2]);
                    maxX = Math.max(maxX, this.source[base]);
                    maxY = Math.max(maxY, this.source[base + 1]);
                    maxZ = Math.max(maxZ, this.source[base + 2]);
                }
                float cx = this.centroids[t * 3];
                float cy = this.centroids[t * 3 + 1];
                float cz = this.centroids[t * 3 + 2];
                cMinX = Math.min(cMinX, cx);
                cMinY = Math.min(cMinY, cy);
                cMinZ = Math.min(cMinZ, cz);
                cMaxX = Math.max(cMaxX, cx);
                cMaxY = Math.max(cMaxY, cy);
                cMaxZ = Math.max(cMaxZ, cz);
            }
            int b = node * 6;
            this.bounds[b] = minX;
            this.bounds[b + 1] = minY;
            this.bounds[b + 2] = minZ;
            this.bounds[b + 3] = maxX;
            this.bounds[b + 4] = maxY;
            this.bounds[b + 5] = maxZ;

            int count = to - from;
            float spanX = cMaxX - cMinX;
            float spanY = cMaxY - cMinY;
            float spanZ = cMaxZ - cMinZ;
            float span = Math.max(spanX, Math.max(spanY, spanZ));
            // A leaf when small enough, or when every centroid coincides and no split could
            // ever separate them (a stack of identical triangles).
            if (count <= LEAF_SIZE || !(span > 0.0F)) {
                this.start[node] = from;
                this.counts[node] = count;
                return;
            }
            int axis = spanX >= spanY && spanX >= spanZ ? 0 : (spanY >= spanZ ? 1 : 2);
            int mid = from + count / 2;
            select(from, to - 1, mid, axis);

            int left = allocate();
            int right = allocate();
            this.start[node] = left;
            this.counts[node] = 0;
            build(from, mid, left);
            build(mid, to, right);
            // right is always left + 1; the traversal relies on that.
            assert right == left + 1;
        }

        /**
         * Quickselect: reorders {@code order[lo..hi]} so the element at {@code k} is the one a
         * full sort by centroid on {@code axis} would put there, with smaller ones before it
         * and larger ones after. Iterative, so a skewed input cannot overflow the stack.
         */
        void select(int lo, int hi, int k, int axis) {
            while (hi > lo) {
                // Median of three as the pivot, which defeats already sorted input.
                int midIndex = (lo + hi) >>> 1;
                float a = key(lo, axis), b = key(midIndex, axis), c = key(hi, axis);
                float pivot = Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
                int i = lo;
                int j = hi;
                while (i <= j) {
                    while (key(i, axis) < pivot) {
                        i++;
                    }
                    while (key(j, axis) > pivot) {
                        j--;
                    }
                    if (i <= j) {
                        int swap = this.order[i];
                        this.order[i] = this.order[j];
                        this.order[j] = swap;
                        i++;
                        j--;
                    }
                }
                if (k <= j) {
                    hi = j;
                } else if (k >= i) {
                    lo = i;
                } else {
                    return;
                }
            }
        }

        private float key(int position, int axis) {
            return this.centroids[this.order[position] * 3 + axis];
        }
    }

    /**
     * Whether a ray hits any triangle at a distance in {@code (tMin, tMax)}. Triangles are
     * two-sided: a back face occludes as much as a front face does.
     *
     * @param stack scratch space of at least {@link #STACK_SIZE} ints, owned by the caller so
     *              the query allocates nothing
     */
    public boolean occluded(float ox, float oy, float oz, float dx, float dy, float dz,
                            float tMin, float tMax, int[] stack) {
        if (this.triangles.length == 0) {
            return false;
        }
        // Infinite reciprocals for zero components are fine: the slab test below handles
        // them through IEEE arithmetic, as long as 0 * inf (NaN) cannot arise, which it can
        // only when the origin lies exactly on a slab plane; Math.min/max then propagate NaN
        // and the node is skipped, a miss we accept for an edge case of measure zero.
        float ix = 1.0F / dx;
        float iy = 1.0F / dy;
        float iz = 1.0F / dz;

        int top = 0;
        stack[top++] = 0;
        while (top > 0) {
            int node = stack[--top];
            int b = node * 6;
            float t1 = (this.nodeBounds[b] - ox) * ix;
            float t2 = (this.nodeBounds[b + 3] - ox) * ix;
            float near = Math.min(t1, t2);
            float far = Math.max(t1, t2);
            t1 = (this.nodeBounds[b + 1] - oy) * iy;
            t2 = (this.nodeBounds[b + 4] - oy) * iy;
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
            t1 = (this.nodeBounds[b + 2] - oz) * iz;
            t2 = (this.nodeBounds[b + 5] - oz) * iz;
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
            if (!(far >= near) || far < tMin || near > tMax) {
                continue;
            }

            int count = this.nodeCount[node];
            if (count == 0) {
                if (top + 2 > stack.length) {
                    // Cannot happen with median splits on any mesh that fits in memory, but a
                    // truncated query is better than an exception halfway through a bake.
                    continue;
                }
                int left = this.nodeStart[node];
                stack[top++] = left + 1;
                stack[top++] = left;
                continue;
            }
            int first = this.nodeStart[node];
            for (int t = first, end = first + count; t < end; t++) {
                if (hit(t, ox, oy, oz, dx, dy, dz, tMin, tMax)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Moller-Trumbore, two-sided. */
    private boolean hit(int triangle, float ox, float oy, float oz, float dx, float dy, float dz,
                        float tMin, float tMax) {
        int base = triangle * 9;
        float[] tri = this.triangles;
        float v0x = tri[base], v0y = tri[base + 1], v0z = tri[base + 2];
        float e1x = tri[base + 3] - v0x, e1y = tri[base + 4] - v0y, e1z = tri[base + 5] - v0z;
        float e2x = tri[base + 6] - v0x, e2y = tri[base + 7] - v0y, e2z = tri[base + 8] - v0z;

        float px = dy * e2z - dz * e2y;
        float py = dz * e2x - dx * e2z;
        float pz = dx * e2y - dy * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (det == 0.0F || !Float.isFinite(det)) {
            return false; // ray parallel to the triangle, or a degenerate triangle
        }
        float inv = 1.0F / det;

        float sx = ox - v0x, sy = oy - v0y, sz = oz - v0z;
        float u = (sx * px + sy * py + sz * pz) * inv;
        if (u < 0.0F || u > 1.0F) {
            return false;
        }
        float qx = sy * e1z - sz * e1y;
        float qy = sz * e1x - sx * e1z;
        float qz = sx * e1y - sy * e1x;
        float v = (dx * qx + dy * qy + dz * qz) * inv;
        if (v < 0.0F || u + v > 1.0F) {
            return false;
        }
        float t = (e2x * qx + e2y * qy + e2z * qz) * inv;
        return t > tMin && t < tMax;
    }
}
