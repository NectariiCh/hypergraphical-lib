package dev.nectariich.hypergraphical.api;

import dev.nectariich.hypergraphical.mesh.MeshBounds;
import dev.nectariich.hypergraphical.mesh.MeshData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a mesh contains, read without uploading it: its bounds, its parts with theirs, and its
 * material elements. Parts keep the order they first appear in the file.
 *
 * @param elements every (part, material) pair the mesh draws, in file order. An element is
 *                 one sub-mesh: the faces of one {@code o} object (or {@code g} group) that
 *                 use one {@code usemtl} material. Elements are what
 *                 {@link ModelInstance#setElementGlow} addresses.
 */
public record MeshInfo(MeshBounds bounds, Map<String, MeshBounds> parts, int triangleCount,
                       List<Element> elements) {

    /**
     * One material element of a part.
     *
     * @param part          the {@code o} (or {@code g}) name the faces belong to
     * @param material      the {@code usemtl} name they are drawn with
     * @param bounds        model-space bounds of just these faces
     * @param triangleCount how many triangles the element has
     */
    public record Element(String part, String material, MeshBounds bounds, int triangleCount) {}

    public MeshInfo {
        elements = elements == null ? List.of() : List.copyOf(elements);
    }

    /**
     * The shape callers used before elements existed. Kept so existing code compiles; the
     * result simply lists no elements.
     */
    public MeshInfo(MeshBounds bounds, Map<String, MeshBounds> parts, int triangleCount) {
        this(bounds, parts, triangleCount, List.of());
    }

    /** Everything above, read from parsed mesh data. */
    public static MeshInfo of(MeshData mesh) {
        List<Element> elements = new ArrayList<>(mesh.subMeshes().size());
        for (MeshData.SubMesh sub : mesh.subMeshes()) {
            elements.add(new Element(sub.part(), sub.name(),
                    MeshBounds.ofRange(mesh.vertices(), mesh.indices(),
                            sub.indexOffset(), sub.indexCount()),
                    sub.indexCount() / 3));
        }
        return new MeshInfo(mesh.bounds(), mesh.partBounds(), mesh.triangleCount(), elements);
    }
}
