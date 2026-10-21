package dev.nectariich.hypergraphical.mesh;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.nectariich.hypergraphical.render.Gl;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL33C.*;

/**
 * A mesh resident on the GPU: one VAO, one vertex buffer, one index buffer, and the sub-mesh
 * ranges within the index buffer.
 *
 * <p>Sub-meshes are index ranges rather than separate buffers, so a multi-material model is
 * still one buffer binding and switching material costs a draw call, not a VAO rebind.
 *
 * <p>Indices narrow to {@code GL_UNSIGNED_SHORT} whenever the mesh fits in 65536 vertices,
 * which is nearly always. That halves index bandwidth for free, and index fetch is one of the
 * few places a high-instance-count draw can actually become memory bound.
 */
public final class GpuMesh implements AutoCloseable {

    /**
     * Vertex attribute location of the per-vertex occlusion ({@code aOcclusion} in
     * {@code pbr.vsh}). 0..3 are the interleaved vertex attributes, 4..10 and 12 belong to
     * the instance stream (see {@code InstanceBuffer}).
     */
    public static final int ATTRIB_OCCLUSION = 11;

    /** One drawable range, paired with the material name it came from. */
    public record SubMesh(String name,
                          long indexByteOffset,
                          int indexCount,
                          ResourceLocation autoMaterial,
                          String part) {}

    private final ResourceLocation id;
    private final int vao;
    private final int vbo;
    /** Per-vertex occlusion, one float each, at {@link #ATTRIB_OCCLUSION}. */
    private final int occlusionVbo;
    private final int ebo;
    private final int indexType;
    private final List<SubMesh> subMeshes;
    private final double boundingRadius;
    private final MeshBounds bounds;
    private final java.util.Map<String, MeshBounds> partBounds;
    private final int vertexCount;
    private final int triangleCount;

    private boolean closed;
    private boolean occlusionBaked;

    private GpuMesh(ResourceLocation id, int vao, int vbo, int occlusionVbo, int ebo, int indexType,
                    List<SubMesh> subMeshes, double boundingRadius, MeshBounds bounds,
                    java.util.Map<String, MeshBounds> partBounds,
                    int vertexCount, int triangleCount) {
        this.id = id;
        this.vao = vao;
        this.vbo = vbo;
        this.occlusionVbo = occlusionVbo;
        this.ebo = ebo;
        this.indexType = indexType;
        this.subMeshes = subMeshes;
        this.boundingRadius = boundingRadius;
        this.bounds = bounds;
        this.partBounds = partBounds;
        this.vertexCount = vertexCount;
        this.triangleCount = triangleCount;
    }

    /** Uploads CPU mesh data. Render thread only. */
    public static GpuMesh upload(ResourceLocation id, MeshData data) {
        int vertexCount = data.vertexCount();
        boolean shortIndices = vertexCount <= 65536;
        int indexType = shortIndices ? GL_UNSIGNED_SHORT : GL_UNSIGNED_INT;
        int indexSize = shortIndices ? Short.BYTES : Integer.BYTES;

        int vao = GlStateManager._glGenVertexArrays();
        int vbo = GlStateManager._glGenBuffers();
        int ebo = GlStateManager._glGenBuffers();

        GlStateManager._glBindVertexArray(vao);

        GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertices = MemoryUtil.memAllocFloat(data.vertices().length);
        try {
            vertices.put(data.vertices()).flip();
            glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(vertices);
        }

        int stride = MeshData.STRIDE_BYTES;
        attrib(0, 3, stride, MeshData.OFF_POSITION);
        attrib(1, 3, stride, MeshData.OFF_NORMAL);
        attrib(2, 2, stride, MeshData.OFF_UV);
        attrib(3, 4, stride, MeshData.OFF_TANGENT);

        // Occlusion is its own buffer rather than a thirteenth float in the interleaved one,
        // because it arrives later: the bake runs in the background after this upload, and
        // patching a separate tightly packed buffer is one glBufferSubData of 4 bytes per
        // vertex instead of re-uploading the whole mesh. Until then every vertex reads 1, so
        // an unbaked mesh, or one that is never baked, renders exactly as it did before.
        int occlusionVbo = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, occlusionVbo);
        FloatBuffer open = MemoryUtil.memAllocFloat(Math.max(1, vertexCount));
        try {
            for (int v = 0; v < vertexCount; v++) {
                open.put(1.0F);
            }
            if (vertexCount == 0) {
                open.put(1.0F);
            }
            open.flip();
            glBufferData(GL_ARRAY_BUFFER, open, GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(open);
        }
        glEnableVertexAttribArray(ATTRIB_OCCLUSION);
        glVertexAttribPointer(ATTRIB_OCCLUSION, 1, GL_FLOAT, false, Float.BYTES, 0L);

        GlStateManager._glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        int[] indices = data.indices();
        ByteBuffer indexBytes = MemoryUtil.memAlloc(indices.length * indexSize);
        try {
            if (shortIndices) {
                for (int index : indices) {
                    indexBytes.putShort((short) index);
                }
            } else {
                for (int index : indices) {
                    indexBytes.putInt(index);
                }
            }
            indexBytes.flip();
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBytes, GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(indexBytes);
        }

        GlStateManager._glBindVertexArray(0);
        GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, 0);
        GlStateManager._glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

        List<SubMesh> subMeshes = data.subMeshes().stream()
                .map(sub -> new SubMesh(sub.name(),
                        (long) sub.indexOffset() * indexSize,
                        sub.indexCount(),
                        sub.autoMaterial(),
                        sub.part()))
                .toList();

        Gl.check("GpuMesh.upload " + id);
        return new GpuMesh(id, vao, vbo, occlusionVbo, ebo, indexType, subMeshes,
                data.boundingRadius(), data.bounds(), data.partBounds(),
                vertexCount, data.triangleCount());
    }

    /**
     * Replaces the per-vertex occlusion with baked values, one per vertex in 0..1. Render
     * thread only. Ignored when the array does not match this mesh's vertex count, which can
     * only mean it was baked for a different version of the mesh.
     */
    public void updateOcclusion(float[] occlusion) {
        if (this.closed || occlusion.length != this.vertexCount || this.vertexCount == 0) {
            return;
        }
        FloatBuffer buffer = MemoryUtil.memAllocFloat(occlusion.length);
        try {
            buffer.put(occlusion).flip();
            GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, this.occlusionVbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0L, buffer);
            GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(buffer);
        }
        this.occlusionBaked = true;
        Gl.check("GpuMesh.updateOcclusion " + this.id);
    }

    /** Whether baked occlusion has been applied; unbaked meshes read 1 everywhere. */
    public boolean occlusionBaked() {
        return this.occlusionBaked;
    }

    public boolean closed() {
        return this.closed;
    }

    private static void attrib(int location, int size, int stride, int offsetFloats) {
        glEnableVertexAttribArray(location);
        glVertexAttribPointer(location, size, GL_FLOAT, false, stride,
                (long) offsetFloats * Float.BYTES);
    }

    public ResourceLocation id() {
        return this.id;
    }

    public List<SubMesh> subMeshes() {
        return this.subMeshes;
    }

    public double boundingRadius() {
        return this.boundingRadius;
    }

    /** Model-space bounds of the whole mesh. */
    public MeshBounds bounds() {
        return this.bounds;
    }

    /** Model-space bounds of each part, keyed by part name. */
    public java.util.Map<String, MeshBounds> partBounds() {
        return this.partBounds;
    }

    public int vertexCount() {
        return this.vertexCount;
    }

    public int triangleCount() {
        return this.triangleCount;
    }

    public void bind() {
        // Instance attribute pointers are also VAO state; InstanceBuffer#bindAttributes sets
        // them on whichever VAO is current, and this VAO is only ever used by our pass.
        GlStateManager._glBindVertexArray(this.vao);
    }

    public void drawInstanced(SubMesh subMesh, int instanceCount) {
        glDrawElementsInstanced(GL_TRIANGLES, subMesh.indexCount(), this.indexType,
                subMesh.indexByteOffset(), instanceCount);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        GlStateManager._glDeleteVertexArrays(this.vao);
        glDeleteBuffers(this.vbo);
        glDeleteBuffers(this.occlusionVbo);
        glDeleteBuffers(this.ebo);
    }
}
