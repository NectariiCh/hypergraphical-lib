package dev.nectariich.hypergraphical.mesh;

import dev.nectariich.hypergraphical.api.OcclusionSettings;
import it.unimi.dsi.fastutil.ints.IntArrays;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

/**
 * Bakes per-vertex ambient occlusion on the CPU.
 *
 * <p>For every vertex, a fixed set of cosine-weighted rays leaves a point nudged a hair off the
 * surface along the vertex normal, and each ray asks the mesh's {@link TriangleBvh} whether
 * anything lies within the search radius. The open fraction {@code 1 - hits / rays} is the
 * occlusion value, then shaped by {@link OcclusionSettings#power()}. All parts of the mesh take
 * part in their rest pose, so a handle shades the body under it.
 *
 * <p><b>Why one shared ray set.</b> Every vertex uses the same Hammersley directions in its own
 * tangent frame, with no per-vertex rotation. Randomising the set per vertex is the usual
 * advice for textures, where a blur hides the noise afterwards; with per-vertex values nothing
 * blurs, and two neighbours that see the same geometry would come out a few percent apart
 * and read as blotches. With a shared set, neighbours with similar normals sample nearly the
 * same directions and the result varies smoothly across a surface.
 *
 * <p><b>Cost.</b> Rays times vertices, each an any-hit BVH query. The bake caps the total at
 * {@link #RAY_BUDGET} by lowering the ray count on very dense meshes, and splits the vertices
 * across the calling fork-join pool, so it belongs on a background thread and never on the
 * render thread.
 *
 * <p><b>Limits.</b> Occlusion lives on vertices and is interpolated across triangles, so a
 * large face with few vertices (a plain plate as two triangles) cannot show a contact shadow
 * in its middle. Dense or subdivided meshes show the most.
 */
public final class OcclusionBaker {

    /** Upper bound on rays cast for one mesh, which bounds the bake time of huge meshes. */
    public static final long RAY_BUDGET = 24_000_000L;

    /** Fewest rays per vertex the budget may lower a bake to. */
    private static final int MIN_RAYS = 16;

    /** Vertices per parallel work item. */
    private static final int CHUNK = 256;

    private OcclusionBaker() {}

    /**
     * Bakes occlusion for every vertex of {@code mesh}.
     *
     * @param cancelled polled between work items; when it turns true the bake stops early
     * @return one value per vertex in 0..1 (1 = fully open), or {@code null} if cancelled
     */
    @Nullable
    public static float[] bake(MeshData mesh, OcclusionSettings settings,
                               BooleanSupplier cancelled) {
        int vertexCount = mesh.vertexCount();
        float[] out = new float[vertexCount];
        Arrays.fill(out, 1.0F);
        if (vertexCount == 0 || mesh.triangleCount() == 0) {
            return out;
        }

        float extent = mesh.bounds().largestSize();
        if (!(extent > 0.0F) || !Float.isFinite(extent)) {
            return out;
        }
        float radius = extent * settings.radiusFraction();
        // The nudge off the surface, relative to the model's size so the bake behaves the
        // same whether the model was exported in metres or millimetres. Large enough to clear
        // float error on the vertex's own triangles, small enough not to skip a real contact.
        float epsilon = extent * 2.0E-4F;

        int rays = settings.rays();
        if ((long) rays * vertexCount > RAY_BUDGET) {
            rays = (int) Math.max(MIN_RAYS, RAY_BUDGET / vertexCount);
        }
        float[] directions = hemisphere(rays);

        TriangleBvh bvh = TriangleBvh.build(mesh.vertices(), MeshData.STRIDE_FLOATS, mesh.indices());
        float[] vertices = mesh.vertices();
        float power = settings.power();
        int sampleCount = rays;
        float[] inward = inwardDirections(vertices, vertexCount, mesh.indices());

        int chunks = (vertexCount + CHUNK - 1) / CHUNK;
        IntStream.range(0, chunks).parallel().forEach(chunk -> {
            if (cancelled.getAsBoolean()) {
                return;
            }
            int[] stack = new int[TriangleBvh.STACK_SIZE];
            int end = Math.min(vertexCount, (chunk + 1) * CHUNK);
            for (int v = chunk * CHUNK; v < end; v++) {
                out[v] = vertexOcclusion(vertices, inward, v, bvh, directions, sampleCount,
                        radius, epsilon, power, stack);
            }
        });
        if (cancelled.getAsBoolean()) {
            return null;
        }

        smoothCoincident(vertices, out);
        return out;
    }

    private static float vertexOcclusion(float[] vertices, float[] inward, int vertex, TriangleBvh bvh,
                                         float[] directions, int rays, float radius,
                                         float epsilon, float power, int[] stack) {
        int base = vertex * MeshData.STRIDE_FLOATS;
        float nx = vertices[base + MeshData.OFF_NORMAL];
        float ny = vertices[base + MeshData.OFF_NORMAL + 1];
        float nz = vertices[base + MeshData.OFF_NORMAL + 2];
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(length > 1.0E-6F)) {
            return 1.0F; // no usable normal, so no hemisphere to sample
        }
        nx /= length;
        ny /= length;
        nz /= length;

        // Orthonormal basis around the normal (Duff et al. 2017, "Building an Orthonormal
        // Basis, Revisited"): branch-light and continuous except across the z = 0 plane.
        float sign = nz >= 0.0F ? 1.0F : -1.0F;
        float a = -1.0F / (sign + nz);
        float b = nx * ny * a;
        float tx = 1.0F + sign * nx * nx * a, ty = sign * b, tz = -sign * nx;
        float bx = b, by = sign + ny * ny * a, bz = -ny;

        // The origin leaves the surface along the normal, so the vertex's own triangles lie a
        // full epsilon behind it and no ray into the hemisphere can hit them.
        //
        // That alone fails for vertices that sit exactly on another surface, like the foot of a
        // wall standing on a plate, or the plate right at the foot of the wall: an origin exactly
        // in the other surface's plane meets it at a distance of zero in every direction, which
        // no hit window can sort out (it either counts every ray or none). Two small sideways
        // moves within the tangent plane take the origin off that plane, and neither may depend
        // on how the tangent frame happens to be oriented: one fixed offset per normal would
        // land inside the blocker on some sides of a feature and outside on the others, so the
        // same contact would come out black on two sides and grey on the other two.
        //
        // 1. Onto the vertex's own surface (see inwardDirections). A wall's foot vertex has its
        //    triangles above it, so this lifts the origin off the plate on every side of the
        //    box, whether the plate is a solid slab or a single sheet. The rays heading into
        //    the plate then hit it and the ones leaving escape. It is a full epsilon, so the
        //    per-ray step below can never undo it.
        // 2. Per ray, half an epsilon further along that ray's own azimuth. A plate vertex at the
        //    foot of a wall is surrounded by plate, so step 1 does not move it; this step puts
        //    the origin inside a closed blocker for rays that head into it (they hit, as they
        //    should) and on the open side for rays that leave (they escape). It is rotation
        //    invariant, so all four sides of a box read the same. A blocker that is a single
        //    sheet standing on the surface (an open fin) is stepped through by the rays heading
        //    into it, so its very foot reads a little too open; closed geometry is exact.
        int in = vertex * 3;
        float ix = inward[in], iy = inward[in + 1], iz = inward[in + 2];
        float along = ix * nx + iy * ny + iz * nz; // keep the move within the tangent plane
        ix -= along * nx;
        iy -= along * ny;
        iz -= along * nz;
        float px = vertices[base] + (nx + ix) * epsilon;
        float py = vertices[base + 1] + (ny + iy) * epsilon;
        float pz = vertices[base + 2] + (nz + iz) * epsilon;
        float slide = epsilon * 0.5F;

        int hits = 0;
        for (int r = 0; r < rays; r++) {
            float lx = directions[r * 3];
            float ly = directions[r * 3 + 1];
            float lz = directions[r * 3 + 2];
            float dx = tx * lx + bx * ly + nx * lz;
            float dy = ty * lx + by * ly + ny * lz;
            float dz = tz * lx + bz * ly + nz * lz;
            // The ray's azimuth in the tangent plane, as a unit vector. Cosine-weighted
            // directions always lean off the normal a little (the disc radius is never zero),
            // so the length is positive; the guard only protects against a degenerate set.
            float planar = (float) Math.sqrt(lx * lx + ly * ly);
            float step = planar > 1.0E-6F ? slide / planar : 0.0F;
            float sx = (tx * lx + bx * ly) * step;
            float sy = (ty * lx + by * ly) * step;
            float sz = (tz * lx + bz * ly) * step;
            if (bvh.occluded(px + sx, py + sy, pz + sz, dx, dy, dz, 0.0F, radius, stack)) {
                hits++;
            }
        }
        float open = 1.0F - hits / (float) rays;
        return power == 1.0F ? open : (float) Math.pow(open, power);
    }

    /**
     * For every vertex, the direction in which its own surface lies, with a length that says how
     * one-sided that is: the sum of the vectors from the vertex to the centroids of its
     * triangles, divided by the sum of their lengths.
     *
     * <p>A vertex inside a surface has triangles all round it, so the vectors cancel and the
     * result is short (exactly zero on a regular grid). A vertex on the edge of its surface, like
     * the bottom row of a wall that stands on a plate, has triangles on one side only, so the
     * result points onto the surface with a length near 1. Vertices split by a UV seam look like
     * edge vertices too; moving their ray origin a hair along their own surface is harmless.
     *
     * @return three floats per vertex
     */
    static float[] inwardDirections(float[] vertices, int vertexCount, int[] indices) {
        float[] sum = new float[vertexCount * 3];
        float[] lengths = new float[vertexCount];
        for (int i = 0; i + 2 < indices.length; i += 3) {
            int ia = indices[i] * MeshData.STRIDE_FLOATS;
            int ib = indices[i + 1] * MeshData.STRIDE_FLOATS;
            int ic = indices[i + 2] * MeshData.STRIDE_FLOATS;
            float cx = (vertices[ia] + vertices[ib] + vertices[ic]) / 3.0F;
            float cy = (vertices[ia + 1] + vertices[ib + 1] + vertices[ic + 1]) / 3.0F;
            float cz = (vertices[ia + 2] + vertices[ib + 2] + vertices[ic + 2]) / 3.0F;
            for (int corner = 0; corner < 3; corner++) {
                int v = indices[i + corner];
                int base = v * MeshData.STRIDE_FLOATS;
                float dx = cx - vertices[base];
                float dy = cy - vertices[base + 1];
                float dz = cz - vertices[base + 2];
                sum[v * 3] += dx;
                sum[v * 3 + 1] += dy;
                sum[v * 3 + 2] += dz;
                lengths[v] += (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }
        for (int v = 0; v < vertexCount; v++) {
            float length = lengths[v];
            for (int axis = 0; axis < 3; axis++) {
                sum[v * 3 + axis] = length > 0.0F ? sum[v * 3 + axis] / length : 0.0F;
            }
        }
        return sum;
    }

    /**
     * Cosine-weighted directions in tangent space (z up) from a Hammersley set: the first
     * coordinate is stratified, the second is the base-2 radical inverse, so the points cover
     * the disc evenly without any randomness.
     */
    static float[] hemisphere(int rays) {
        float[] out = new float[rays * 3];
        for (int i = 0; i < rays; i++) {
            double u = (i + 0.5D) / rays;
            double w = Integer.toUnsignedLong(Integer.reverse(i)) * 0x1p-32D;
            double r = Math.sqrt(u);
            double phi = 2.0D * Math.PI * w;
            out[i * 3] = (float) (r * Math.cos(phi));
            out[i * 3 + 1] = (float) (r * Math.sin(phi));
            out[i * 3 + 2] = (float) Math.sqrt(Math.max(0.0D, 1.0D - u));
        }
        return out;
    }

    /**
     * Averages occlusion across vertices that share a position and roughly share a normal.
     *
     * <p>The importer welds on (position, uv, normal), so a UV seam leaves two vertices at the
     * same point with the same normal. Their rays leave from the same place and should agree;
     * averaging them removes the last bit of disagreement that would show as a hard line along
     * the seam. Vertices on either side of a hard edge have different normals and see
     * different hemispheres, so they are deliberately left apart.
     */
    private static void smoothCoincident(float[] vertices, float[] occlusion) {
        int count = occlusion.length;
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        IntArrays.quickSort(order, (left, right) -> comparePositions(vertices, left, right));

        float[] smoothed = occlusion.clone();
        int start = 0;
        while (start < count) {
            int end = start + 1;
            while (end < count && comparePositions(vertices, order[start], order[end]) == 0) {
                end++;
            }
            if (end - start > 1) {
                for (int i = start; i < end; i++) {
                    int vi = order[i];
                    float sum = 0.0F;
                    int members = 0;
                    for (int j = start; j < end; j++) {
                        int vj = order[j];
                        if (normalDot(vertices, vi, vj) > 0.5F) {
                            sum += occlusion[vj];
                            members++;
                        }
                    }
                    if (members > 0) {
                        smoothed[vi] = sum / members;
                    }
                }
            }
            start = end;
        }
        System.arraycopy(smoothed, 0, occlusion, 0, count);
    }

    private static int comparePositions(float[] vertices, int left, int right) {
        int a = left * MeshData.STRIDE_FLOATS;
        int b = right * MeshData.STRIDE_FLOATS;
        for (int axis = 0; axis < 3; axis++) {
            int compare = Float.compare(vertices[a + axis], vertices[b + axis]);
            if (compare != 0) {
                return compare;
            }
        }
        return 0;
    }

    private static float normalDot(float[] vertices, int left, int right) {
        int a = left * MeshData.STRIDE_FLOATS + MeshData.OFF_NORMAL;
        int b = right * MeshData.STRIDE_FLOATS + MeshData.OFF_NORMAL;
        return vertices[a] * vertices[b] + vertices[a + 1] * vertices[b + 1]
                + vertices[a + 2] * vertices[b + 2];
    }
}
