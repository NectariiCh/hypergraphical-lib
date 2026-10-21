import dev.nectariich.hypergraphical.api.MeshInfo;
import dev.nectariich.hypergraphical.api.OcclusionSettings;
import dev.nectariich.hypergraphical.mesh.MeshData;
import dev.nectariich.hypergraphical.mesh.OcclusionBaker;
import dev.nectariich.hypergraphical.mesh.TriangleBvh;
import dev.nectariich.hypergraphical.mesh.obj.ObjImportSettings;
import dev.nectariich.hypergraphical.mesh.obj.ObjMeshReader;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Offline validator for the ambient occlusion bake and the mesh introspection it ships with.
 *
 * <ul>
 *   <li><b>BVH agrees with brute force.</b> Random rays against a random triangle soup, any-hit
 *       through {@link TriangleBvh} versus testing every triangle. A BVH bug shows up as
 *       missing contact shadows, which is easy to mistake for a tuning problem.</li>
 *   <li><b>The bake looks right on a known scene.</b> A finely tessellated plate with a box
 *       standing on it: the open plate far from the box and the top of the box stay bright,
 *       the plate right at the foot of the box darkens clearly, and the walls darken towards
 *       the plate.</li>
 *   <li><b>No directional bias.</b> The same box on a closed plate (top and underside): the
 *       plate at the box foot and the wall at the plate read the same on all four sides. A
 *       ray origin nudged one fixed way per normal would land inside the box or the slab on
 *       some sides only, which shows up here as two black sides and two grey ones.</li>
 *   <li><b>Determinism.</b> Two bakes of the same mesh are bit-identical.</li>
 *   <li><b>Scale.</b> A mesh over 100k triangles bakes in a sensible time.</li>
 *   <li><b>Elements.</b> {@link MeshInfo#of} lists one element per sub-mesh, in file order.</li>
 * </ul>
 *
 * <p>Not part of the mod jar. Run with the main classes and Minecraft on the classpath, e.g.
 * {@code java -cp <main classes>:<tools classes>:<minecraft classpath> AoCheck <assets-root>}.
 */
public final class AoCheck {

    private static int failures;

    public static void main(String[] args) throws IOException {
        checkBvh();
        checkPlateAndBox();
        checkSymmetry(true);
        checkSymmetry(false);
        checkLarge();
        if (args.length > 0) {
            checkElements(Path.of(args[0]));
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("Occlusion bake checks passed.");
        } else {
            System.out.println(failures + " check(s) failed.");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- BVH -------------------------------------------------------------------
    private static void checkBvh() {
        System.out.printf("%n=== BVH against brute force%n");
        Random random = new Random(1234);
        int triangles = 3000;
        List<float[]> soup = new ArrayList<>();
        for (int t = 0; t < triangles; t++) {
            float cx = random.nextFloat() * 10, cy = random.nextFloat() * 10, cz = random.nextFloat() * 10;
            float[] tri = new float[9];
            for (int i = 0; i < 9; i++) {
                tri[i] = (i % 3 == 0 ? cx : i % 3 == 1 ? cy : cz) + (random.nextFloat() - 0.5F);
            }
            soup.add(tri);
        }
        MeshData mesh = fromTriangles(soup);
        TriangleBvh bvh = TriangleBvh.build(mesh.vertices(), MeshData.STRIDE_FLOATS, mesh.indices());
        int[] stack = new int[TriangleBvh.STACK_SIZE];

        int disagreements = 0;
        int hits = 0;
        int rays = 20000;
        for (int r = 0; r < rays; r++) {
            float ox = random.nextFloat() * 12 - 1, oy = random.nextFloat() * 12 - 1, oz = random.nextFloat() * 12 - 1;
            float dx = (float) random.nextGaussian(), dy = (float) random.nextGaussian(), dz = (float) random.nextGaussian();
            float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= length;
            dy /= length;
            dz /= length;
            float tMax = random.nextFloat() * 6;
            boolean fast = bvh.occluded(ox, oy, oz, dx, dy, dz, 0.0F, tMax, stack);
            boolean slow = false;
            for (float[] tri : soup) {
                if (bruteHit(tri, ox, oy, oz, dx, dy, dz, tMax)) {
                    slow = true;
                    break;
                }
            }
            if (fast != slow) {
                disagreements++;
            }
            if (slow) {
                hits++;
            }
        }
        System.out.printf("  %d nodes, %d of %d rays hit, %d disagreement(s)%n",
                bvh.nodeCount(), hits, rays, disagreements);
        expect("BVH any-hit equals brute force", disagreements == 0);
        expect("the test actually exercises hits and misses", hits > rays / 20 && hits < rays - rays / 20);
    }

    private static boolean bruteHit(float[] tri, float ox, float oy, float oz,
                                    float dx, float dy, float dz, float tMax) {
        double e1x = tri[3] - tri[0], e1y = tri[4] - tri[1], e1z = tri[5] - tri[2];
        double e2x = tri[6] - tri[0], e2y = tri[7] - tri[1], e2z = tri[8] - tri[2];
        double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
        double det = e1x * px + e1y * py + e1z * pz;
        if (det == 0) {
            return false;
        }
        double sx = ox - tri[0], sy = oy - tri[1], sz = oz - tri[2];
        double u = (sx * px + sy * py + sz * pz) / det;
        if (u < 0 || u > 1) {
            return false;
        }
        double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
        double v = (dx * qx + dy * qy + dz * qz) / det;
        if (v < 0 || u + v > 1) {
            return false;
        }
        double t = (e2x * qx + e2y * qy + e2z * qz) / det;
        return t > 0 && t < tMax;
    }

    // ---- plate and box -----------------------------------------------------------
    private static void checkPlateAndBox() {
        System.out.printf("%n=== Plate with a box standing on it%n");
        // 16 x 16 plate, 64 x 64 cells, box 4 x 4 x 4 in the middle.
        Builder builder = new Builder();
        builder.grid(0, 0, 0, 16, 16, 64);
        builder.box(6, 0, 6, 10, 4, 10, 16);
        MeshData mesh = builder.build();
        System.out.printf("  %d vertices, %d triangles%n", mesh.vertexCount(), mesh.triangleCount());

        OcclusionSettings settings = OcclusionSettings.STRONG;
        long started = System.nanoTime();
        float[] ao = OcclusionBaker.bake(mesh, settings, () -> false);
        System.out.printf("  baked in %d ms%n", (System.nanoTime() - started) / 1_000_000L);
        float[] again = OcclusionBaker.bake(mesh, settings, () -> false);
        expect("bake returns one value per vertex", ao != null && ao.length == mesh.vertexCount());
        expect("bake is deterministic", java.util.Arrays.equals(ao, again));

        float farPlate = sample(mesh, ao, 0.5F, 0.0F, 0.5F, 0, 1, 0);
        float nearPlate = sample(mesh, ao, 5.75F, 0.0F, 8.0F, 0, 1, 0);
        float boxTop = sample(mesh, ao, 8.0F, 4.0F, 8.0F, 0, 1, 0);
        float wallFoot = sample(mesh, ao, 6.0F, 0.0F, 8.0F, -1, 0, 0);
        float wallTop = sample(mesh, ao, 6.0F, 4.0F, 8.0F, -1, 0, 0);
        System.out.printf("  far plate %.3f, plate at the box foot %.3f, box top %.3f%n",
                farPlate, nearPlate, boxTop);
        System.out.printf("  wall at the foot %.3f, wall at the top %.3f%n", wallFoot, wallTop);
        expect("open plate stays bright (> 0.97)", farPlate > 0.97F);
        expect("box top stays bright (> 0.97)", boxTop > 0.97F);
        expect("plate at the foot of the box darkens strongly (< 0.45)", nearPlate < 0.45F);
        expect("wall darkens towards the plate", wallFoot < wallTop - 0.2F);

        boolean[] cancelledAfterStart = {false};
        float[] cancelled = OcclusionBaker.bake(mesh, settings, () -> cancelledAfterStart[0] = true);
        expect("a cancelled bake returns null", cancelled == null);
    }

    /**
     * The plate and box again, either with the plate closed underneath so it is a solid slab
     * like a real panel, or as a single sheet like a front-only export. A contact line must not
     * depend on which side of the box it is on, and must be neither black nor fully open.
     */
    private static void checkSymmetry(boolean solid) {
        System.out.printf("%n=== Contact lines on all four sides of a box on a %s plate%n",
                solid ? "solid" : "single-sheet");
        Builder builder = new Builder();
        builder.grid(0, 0, 0, 16, 16, 64);
        if (solid) {
            builder.underside(0, -1, 0, 16, 16, 16);
        }
        builder.box(6, 0, 6, 10, 4, 10, 16);
        MeshData mesh = builder.build();
        float[] ao = OcclusionBaker.bake(mesh, OcclusionSettings.STRONG, () -> false);

        float[] plate = {
                sample(mesh, ao, 6.0F, 0.0F, 8.0F, 0, 1, 0),   // west foot
                sample(mesh, ao, 10.0F, 0.0F, 8.0F, 0, 1, 0),  // east foot
                sample(mesh, ao, 8.0F, 0.0F, 6.0F, 0, 1, 0),   // north foot
                sample(mesh, ao, 8.0F, 0.0F, 10.0F, 0, 1, 0)}; // south foot
        float[] wall = {
                sample(mesh, ao, 6.0F, 0.0F, 8.0F, -1, 0, 0),
                sample(mesh, ao, 10.0F, 0.0F, 8.0F, 1, 0, 0),
                sample(mesh, ao, 8.0F, 0.0F, 6.0F, 0, 0, -1),
                sample(mesh, ao, 8.0F, 0.0F, 10.0F, 0, 0, 1)};
        System.out.printf("  plate at the foot W %.3f, E %.3f, N %.3f, S %.3f%n",
                plate[0], plate[1], plate[2], plate[3]);
        System.out.printf("  wall at the plate W %.3f, E %.3f, N %.3f, S %.3f%n",
                wall[0], wall[1], wall[2], wall[3]);
        expect("plate foot matches on all four sides (spread < 0.06)", spread(plate) < 0.06F);
        expect("wall foot matches on all four sides (spread < 0.06)", spread(wall) < 0.06F);
        expect("plate foot is half occluded, not black (> 0.1)", min(plate) > 0.1F);
        expect("wall foot is half occluded, not black (> 0.1)", min(wall) > 0.1F);
        expect("wall foot is darkened by the plate (< 0.8)", min(wall) + spread(wall) < 0.8F); // the largest value
    }

    private static float spread(float[] values) {
        float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
        for (float value : values) {
            low = Math.min(low, value);
            high = Math.max(high, value);
        }
        return high - low;
    }

    private static float min(float[] values) {
        float low = Float.MAX_VALUE;
        for (float value : values) {
            low = Math.min(low, value);
        }
        return low;
    }

    private static void checkLarge() {
        System.out.printf("%n=== Large mesh%n");
        Builder builder = new Builder();
        builder.grid(0, 0, 0, 16, 16, 200);
        for (int i = 0; i < 6; i++) {
            builder.box(1 + i * 2.5F, 0, 4, 2 + i * 2.5F, 1 + i, 12, 24);
        }
        MeshData mesh = builder.build();
        long started = System.nanoTime();
        float[] ao = OcclusionBaker.bake(mesh, OcclusionSettings.STRONG, () -> false);
        long millis = (System.nanoTime() - started) / 1_000_000L;
        System.out.printf("  %d vertices, %d triangles, baked in %d ms on %d core(s)%n",
                mesh.vertexCount(), mesh.triangleCount(), millis,
                Runtime.getRuntime().availableProcessors());
        expect("over 100k triangles", mesh.triangleCount() > 100_000);
        expect("large bake finishes in under 60 s", ao != null && millis < 60_000L);
    }

    private static float sample(MeshData mesh, float[] ao, float x, float y, float z,
                                float nx, float ny, float nz) {
        float best = Float.MAX_VALUE;
        float value = Float.NaN;
        float[] v = mesh.vertices();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int b = i * MeshData.STRIDE_FLOATS;
            float dot = v[b + 3] * nx + v[b + 4] * ny + v[b + 5] * nz;
            if (dot < 0.9F) {
                continue;
            }
            float dx = v[b] - x, dy = v[b + 1] - y, dz = v[b + 2] - z;
            float d = dx * dx + dy * dy + dz * dz;
            if (d < best) {
                best = d;
                value = ao[i];
            }
        }
        return value;
    }

    // ---- elements -------------------------------------------------------------------
    private static void checkElements(Path root) throws IOException {
        System.out.printf("%n=== MeshInfo elements%n");
        String relative = "models/obj/test_cube.obj";
        ObjMeshReader.Result result;
        try (InputStream in = Files.newInputStream(root.resolve(relative))) {
            result = ObjMeshReader.read(in, new ResourceLocation("hypergraphical", relative),
                    ObjImportSettings.DEFAULT, mtl -> {
                        Path resolved = root.resolve(mtl.getPath());
                        return Files.exists(resolved) ? Files.newInputStream(resolved) : null;
                    });
        }
        MeshInfo info = MeshInfo.of(result.mesh());
        int triangles = 0;
        for (MeshInfo.Element element : info.elements()) {
            System.out.printf("  %s#%s: %d tris, bounds %s%n", element.part(), element.material(),
                    element.triangleCount(), element.bounds());
            triangles += element.triangleCount();
        }
        expect("one element per sub-mesh", info.elements().size() == result.mesh().subMeshes().size());
        expect("element triangles add up", triangles == info.triangleCount());
        boolean ordered = true;
        for (int i = 0; i < info.elements().size(); i++) {
            MeshData.SubMesh sub = result.mesh().subMeshes().get(i);
            MeshInfo.Element element = info.elements().get(i);
            ordered &= sub.part().equals(element.part()) && sub.name().equals(element.material());
        }
        expect("elements are in file order", ordered);
    }

    // ---- mesh building ----------------------------------------------------------------
    private static MeshData fromTriangles(List<float[]> triangles) {
        Builder builder = new Builder();
        for (float[] tri : triangles) {
            int a = builder.vertex(tri[0], tri[1], tri[2], 0, 1, 0);
            int b = builder.vertex(tri[3], tri[4], tri[5], 0, 1, 0);
            int c = builder.vertex(tri[6], tri[7], tri[8], 0, 1, 0);
            builder.triangle(a, b, c);
        }
        return builder.build();
    }

    private static final class Builder {
        final List<Float> vertices = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();
        int count;

        int vertex(float x, float y, float z, float nx, float ny, float nz) {
            float[] values = {x, y, z, nx, ny, nz, 0, 0, 1, 0, 0, 1};
            for (float value : values) {
                this.vertices.add(value);
            }
            return this.count++;
        }

        void triangle(int a, int b, int c) {
            this.indices.add(a);
            this.indices.add(b);
            this.indices.add(c);
        }

        /** A tessellated horizontal rectangle at height {@code y0}, facing +Y. */
        void grid(float x0, float y0, float z0, float x1, float z1, int cells) {
            int first = this.count;
            for (int j = 0; j <= cells; j++) {
                for (int i = 0; i <= cells; i++) {
                    float x = x0 + (x1 - x0) * i / cells;
                    float z = z0 + (z1 - z0) * j / cells;
                    vertex(x, y0, z, 0, 1, 0);
                }
            }
            for (int j = 0; j < cells; j++) {
                for (int i = 0; i < cells; i++) {
                    int a = first + j * (cells + 1) + i;
                    int b = a + 1;
                    int c = a + cells + 1;
                    int d = c + 1;
                    triangle(a, c, b);
                    triangle(b, c, d);
                }
            }
        }

        /** A tessellated horizontal rectangle at height {@code y0}, facing -Y (a slab's underside). */
        void underside(float x0, float y0, float z0, float x1, float z1, int cells) {
            face(new float[] {x0, y0, z0}, new float[] {0, 0, z1 - z0}, new float[] {x1 - x0, 0, 0},
                    new float[] {0, -1, 0}, cells);
        }

        /** A box without a bottom face, each face tessellated with {@code cells} per side. */
        void box(float x0, float y0, float z0, float x1, float y1, float z1, int cells) {
            face(new float[] {x0, y1, z0}, new float[] {x1 - x0, 0, 0}, new float[] {0, 0, z1 - z0}, new float[] {0, 1, 0}, cells);
            face(new float[] {x0, y0, z0}, new float[] {0, 0, z1 - z0}, new float[] {0, y1 - y0, 0}, new float[] {-1, 0, 0}, cells);
            face(new float[] {x1, y0, z0}, new float[] {0, y1 - y0, 0}, new float[] {0, 0, z1 - z0}, new float[] {1, 0, 0}, cells);
            face(new float[] {x0, y0, z0}, new float[] {0, y1 - y0, 0}, new float[] {x1 - x0, 0, 0}, new float[] {0, 0, -1}, cells);
            face(new float[] {x0, y0, z1}, new float[] {x1 - x0, 0, 0}, new float[] {0, y1 - y0, 0}, new float[] {0, 0, 1}, cells);
        }

        void face(float[] origin, float[] u, float[] v, float[] n, int cells) {
            int first = this.count;
            for (int j = 0; j <= cells; j++) {
                for (int i = 0; i <= cells; i++) {
                    float a = (float) i / cells, b = (float) j / cells;
                    vertex(origin[0] + u[0] * a + v[0] * b, origin[1] + u[1] * a + v[1] * b,
                            origin[2] + u[2] * a + v[2] * b, n[0], n[1], n[2]);
                }
            }
            for (int j = 0; j < cells; j++) {
                for (int i = 0; i < cells; i++) {
                    int a = first + j * (cells + 1) + i;
                    triangle(a, a + 1, a + cells + 1);
                    triangle(a + 1, a + cells + 2, a + cells + 1);
                }
            }
        }

        MeshData build() {
            float[] v = new float[this.vertices.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = this.vertices.get(i);
            }
            int[] idx = new int[this.indices.size()];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = this.indices.get(i);
            }
            float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int i = 0; i < v.length; i += MeshData.STRIDE_FLOATS) {
                for (int axis = 0; axis < 3; axis++) {
                    min[axis] = Math.min(min[axis], v[i + axis]);
                    max[axis] = Math.max(max[axis], v[i + axis]);
                }
            }
            return new MeshData(v, idx, List.of(new MeshData.SubMesh("default", 0, idx.length, null, "default")),
                    min, max);
        }
    }

    private static void expect(String what, boolean condition) {
        if (!condition) {
            failures++;
            System.out.println("  FAIL " + what);
        } else {
            System.out.println("  ok   " + what);
        }
    }
}
