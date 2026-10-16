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

    /** One drawable range, paired with the material name it came from. */
    public record SubMesh(String name,
                          long indexByteOffset,
                          int indexCount,
                          ResourceLocation autoMaterial) {}

    private final ResourceLocation id;
    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int indexType;
    private final List<SubMesh> subMeshes;
    private final double boundingRadius;
    private final int vertexCount;
    private final int triangleCount;

    private boolean closed;

    private GpuMesh(ResourceLocation id, int vao, int vbo, int ebo, int indexType,
                    List<SubMesh> subMeshes, double boundingRadius,
                    int vertexCount, int triangleCount) {
        this.id = id;
        this.vao = vao;
        this.vbo = vbo;
        this.ebo = ebo;
        this.indexType = indexType;
        this.subMeshes = subMeshes;
        this.boundingRadius = boundingRadius;
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
                        sub.autoMaterial()))
                .toList();

        Gl.check("GpuMesh.upload " + id);
        return new GpuMesh(id, vao, vbo, ebo, indexType, subMeshes,
                data.boundingRadius(), vertexCount, data.triangleCount());
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
        glDeleteBuffers(this.ebo);
    }
}
