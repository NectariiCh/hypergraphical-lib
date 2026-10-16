import dev.nectariich.hypergraphical.mesh.MeshData;
import dev.nectariich.hypergraphical.mesh.obj.ObjImportSettings;
import dev.nectariich.hypergraphical.mesh.obj.ObjMeshReader;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Offline validator for the OBJ importer.
 *
 * <p>Runs {@link ObjMeshReader} against the shipped test assets and asserts the invariants the
 * renderer depends on but cannot check at runtime without costing frames:
 *
 * <ul>
 *   <li>indices in range, sub-mesh ranges contiguous and covering the whole index buffer</li>
 *   <li>normals unit length, tangents unit length and orthogonal to their normal</li>
 *   <li>no NaN or infinity anywhere in the vertex buffer</li>
 *   <li><b>winding agrees with the normals</b>, per triangle. This is the one that matters:
 *       geometry wound the wrong way is invisible under backface culling and looks like a
 *       loading bug rather than an asset bug, and it is not otherwise detectable without
 *       looking at the model in game.</li>
 * </ul>
 *
 * <p>Not part of the mod jar. Run it through {@code tools/check-obj.sh}.
 */
public final class ObjCheck {

    private static int failures;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: ObjCheck <assets-root>");
            System.exit(2);
        }
        Path root = Path.of(args[0]);

        check(root, "models/obj/test_sphere.obj", 1);
        check(root, "models/obj/test_cube.obj", 2);

        System.out.println();
        if (failures == 0) {
            System.out.println("OBJ importer checks passed.");
        } else {
            System.out.println(failures + " check(s) failed.");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(Path root, String relative, int expectedSubMeshes)
            throws IOException {
        Path file = root.resolve(relative);
        ResourceLocation id = new ResourceLocation("hypergraphical", relative);

        ObjMeshReader.Result result;
        try (InputStream in = Files.newInputStream(file)) {
            result = ObjMeshReader.read(in, id, ObjImportSettings.DEFAULT, mtl -> {
                // Resolve mtllib the way the resource manager would: same namespace, same
                // directory layout, read straight off disk.
                Path resolved = root.resolve(mtl.getPath());
                return Files.exists(resolved) ? Files.newInputStream(resolved) : null;
            });
        }

        MeshData mesh = result.mesh();
        System.out.printf("%n=== %s%n", relative);
        System.out.printf("  %d vertices, %d triangles, %d sub-mesh(es), %d MTL entry(ies)%n",
                mesh.vertexCount(), mesh.triangleCount(), mesh.subMeshes().size(),
                result.materials().entries().size());
        System.out.printf("  bounds min [%.3f %.3f %.3f] max [%.3f %.3f %.3f] radius %.4f%n",
                mesh.boundsMin()[0], mesh.boundsMin()[1], mesh.boundsMin()[2],
                mesh.boundsMax()[0], mesh.boundsMax()[1], mesh.boundsMax()[2],
                mesh.boundingRadius());
        for (MeshData.SubMesh sub : mesh.subMeshes()) {
            System.out.printf("  sub-mesh '%s': %d indices at %d -> %s%n",
                    sub.name(), sub.indexCount(), sub.indexOffset(), sub.autoMaterial());
        }

        expect(relative, "at least one triangle", mesh.triangleCount() > 0);
        expect(relative, "expected " + expectedSubMeshes + " sub-mesh(es)",
                mesh.subMeshes().size() == expectedSubMeshes);

        checkFinite(relative, mesh);
        checkIndices(relative, mesh);
        checkSubMeshCoverage(relative, mesh);
        checkNormalsAndTangents(relative, mesh);
        checkWinding(relative, mesh);

        if (relative.contains("sphere")) {
            checkSphere(relative, mesh);
        }
    }

    private static void checkFinite(String label, MeshData mesh) {
        for (float value : mesh.vertices()) {
            if (!Float.isFinite(value)) {
                expect(label, "vertex buffer is finite", false);
                return;
            }
        }
        expect(label, "vertex buffer is finite", true);
    }

    private static void checkIndices(String label, MeshData mesh) {
        int vertexCount = mesh.vertexCount();
        for (int index : mesh.indices()) {
            if (index < 0 || index >= vertexCount) {
                expect(label, "indices in range", false);
                return;
            }
        }
        expect(label, "indices in range", true);
        expect(label, "index count divisible by 3", mesh.indices().length % 3 == 0);
    }

    private static void checkSubMeshCoverage(String label, MeshData mesh) {
        int cursor = 0;
        boolean ok = true;
        for (MeshData.SubMesh sub : mesh.subMeshes()) {
            if (sub.indexOffset() != cursor || sub.indexCount() % 3 != 0) {
                ok = false;
                break;
            }
            cursor += sub.indexCount();
        }
        expect(label, "sub-meshes are contiguous and cover the index buffer",
                ok && cursor == mesh.indices().length);
    }

    private static void checkNormalsAndTangents(String label, MeshData mesh) {
        float worstNormal = 0.0F;
        float worstTangent = 0.0F;
        float worstOrtho = 0.0F;

        for (int v = 0; v < mesh.vertexCount(); v++) {
            int base = v * MeshData.STRIDE_FLOATS;
            float[] n = read3(mesh, base + MeshData.OFF_NORMAL);
            float[] t = read3(mesh, base + MeshData.OFF_TANGENT);
            float w = mesh.vertices()[base + MeshData.OFF_TANGENT + 3];

            worstNormal = Math.max(worstNormal, Math.abs(length(n) - 1.0F));
            worstTangent = Math.max(worstTangent, Math.abs(length(t) - 1.0F));
            worstOrtho = Math.max(worstOrtho, Math.abs(dot(n, t)));

            if (w != 1.0F && w != -1.0F) {
                expect(label, "tangent handedness is +/-1", false);
                return;
            }
        }

        System.out.printf("  worst |n|-1 = %.2e, |t|-1 = %.2e, |dot(n,t)| = %.2e%n",
                worstNormal, worstTangent, worstOrtho);
        expect(label, "normals are unit length", worstNormal < 1.0E-4F);
        expect(label, "tangents are unit length", worstTangent < 1.0E-4F);
        expect(label, "tangents are orthogonal to normals", worstOrtho < 1.0E-4F);
        expect(label, "tangent handedness is +/-1", true);
    }

    /**
     * Every triangle's geometric normal, from its winding, should point the same way as its
     * vertex normals. A negative dot product means the face is wound backwards and will be
     * culled away.
     */
    private static void checkWinding(String label, MeshData mesh) {
        int[] indices = mesh.indices();
        int wrong = 0;

        for (int i = 0; i + 2 < indices.length; i += 3) {
            float[] p0 = read3(mesh, indices[i] * MeshData.STRIDE_FLOATS);
            float[] p1 = read3(mesh, indices[i + 1] * MeshData.STRIDE_FLOATS);
            float[] p2 = read3(mesh, indices[i + 2] * MeshData.STRIDE_FLOATS);

            float[] e1 = {p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]};
            float[] e2 = {p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]};
            float[] geometric = cross(e1, e2);
            if (length(geometric) < 1.0E-9F) {
                continue; // degenerate triangle, e.g. a UV-sphere pole fan
            }

            float agreement = 0.0F;
            for (int corner = 0; corner < 3; corner++) {
                float[] n = read3(mesh, indices[i + corner] * MeshData.STRIDE_FLOATS
                        + MeshData.OFF_NORMAL);
                agreement += dot(geometric, n);
            }
            if (agreement < 0.0F) {
                wrong++;
            }
        }

        System.out.printf("  %d triangle(s) wound against their normals%n", wrong);
        expect(label, "winding agrees with normals (counter-clockwise from outside)", wrong == 0);
    }

    /** Sphere-specific: radius and outward normals, which pin down the generator's output. */
    private static void checkSphere(String label, MeshData mesh) {
        float worstRadius = 0.0F;
        float worstOutward = 1.0F;

        for (int v = 0; v < mesh.vertexCount(); v++) {
            int base = v * MeshData.STRIDE_FLOATS;
            float[] p = read3(mesh, base);
            float[] n = read3(mesh, base + MeshData.OFF_NORMAL);
            float radius = length(p);
            worstRadius = Math.max(worstRadius, Math.abs(radius - 0.5F));
            if (radius > 1.0E-5F) {
                float[] outward = {p[0] / radius, p[1] / radius, p[2] / radius};
                worstOutward = Math.min(worstOutward, dot(outward, n));
            }
        }

        System.out.printf("  sphere: worst |r-0.5| = %.2e, min dot(outward, n) = %.4f%n",
                worstRadius, worstOutward);
        expect(label, "all vertices lie on radius 0.5", worstRadius < 1.0E-5F);
        expect(label, "normals point outward", worstOutward > 0.999F);
    }

    // ---- helpers -----------------------------------------------------------
    private static float[] read3(MeshData mesh, int offset) {
        return new float[] {
                mesh.vertices()[offset], mesh.vertices()[offset + 1], mesh.vertices()[offset + 2]};
    }

    private static float length(float[] v) {
        return (float) Math.sqrt(dot(v, v));
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] {
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static void expect(String label, String what, boolean condition) {
        if (!condition) {
            failures++;
            System.out.println("  FAIL " + what);
        } else {
            System.out.println("  ok   " + what);
        }
    }
}
