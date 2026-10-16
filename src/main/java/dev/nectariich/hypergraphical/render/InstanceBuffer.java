package dev.nectariich.hypergraphical.render;

import com.mojang.blaze3d.platform.GlStateManager;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * One streaming vertex buffer holding every instance drawn this frame.
 *
 * <p>All batches share a single buffer and a single upload. The alternative &mdash; a buffer per
 * batch &mdash; means a {@code glBufferData} and a driver-side allocation per material, which
 * dominates the frame long before the GPU notices the geometry.
 *
 * <p>The buffer is orphaned each frame ({@code glBufferData} with a null pointer before the
 * real upload). That tells the driver the old contents are dead so it can hand back fresh
 * memory instead of stalling until the previous frame's draws have finished reading. It is
 * the cheap, portable version of persistent mapping with manual fencing, and on a
 * once-per-frame upload of a few megabytes the difference is not measurable.
 *
 * <p>Per-instance layout, {@value #FLOATS_PER_INSTANCE} floats:
 * <pre>
 *   0..15   mat4  model matrix, camera-relative
 *   16..19  vec4  tint rgba
 *   20..23  vec4  metallic, smoothness, emissiveStrength, probeBlend
 *   24..27  vec4  lightmap block, lightmap sky, uv offset x, uv offset y
 * </pre>
 */
public final class InstanceBuffer implements AutoCloseable {

    public static final int FLOATS_PER_INSTANCE = 28;
    public static final int BYTES_PER_INSTANCE = FLOATS_PER_INSTANCE * Float.BYTES;

    /** First instance attribute location; see the vertex shader. */
    public static final int ATTRIB_MODEL = 4;
    public static final int ATTRIB_TINT = 8;
    public static final int ATTRIB_PARAMS = 9;
    public static final int ATTRIB_LIGHT_UV = 10;

    private int vbo;
    private FloatBuffer staging;
    private int capacityInstances;
    private int count;

    public InstanceBuffer(int initialInstances) {
        this.capacityInstances = Math.max(64, initialInstances);
        this.staging = MemoryUtil.memAllocFloat(this.capacityInstances * FLOATS_PER_INSTANCE);
    }

    private void ensureVbo() {
        if (this.vbo == 0) {
            this.vbo = GlStateManager._glGenBuffers();
        }
    }

    public int vbo() {
        return this.vbo;
    }

    public int count() {
        return this.count;
    }

    public void begin() {
        this.count = 0;
        this.staging.clear();
    }

    private void ensureCapacity(int extraInstances) {
        int needed = this.count + extraInstances;
        if (needed <= this.capacityInstances) {
            return;
        }
        int newCapacity = this.capacityInstances;
        while (newCapacity < needed) {
            newCapacity <<= 1;
        }
        FloatBuffer grown = MemoryUtil.memAllocFloat(newCapacity * FLOATS_PER_INSTANCE);
        this.staging.flip();
        grown.put(this.staging);
        MemoryUtil.memFree(this.staging);
        this.staging = grown;
        this.capacityInstances = newCapacity;
    }

    /**
     * Appends one instance and returns its index. The model matrix is written column-major,
     * which is what {@code glVertexAttribPointer} on four consecutive vec4s expects.
     */
    public int push(Matrix4f model,
                    float tintR, float tintG, float tintB, float tintA,
                    float metallic, float smoothness, float emissive, float probeBlend,
                    float lightBlock, float lightSky, float uvOffsetU, float uvOffsetV) {
        ensureCapacity(1);
        int index = this.count++;
        int base = index * FLOATS_PER_INSTANCE;

        model.get(base, this.staging);

        this.staging.put(base + 16, tintR);
        this.staging.put(base + 17, tintG);
        this.staging.put(base + 18, tintB);
        this.staging.put(base + 19, tintA);

        this.staging.put(base + 20, metallic);
        this.staging.put(base + 21, smoothness);
        this.staging.put(base + 22, emissive);
        this.staging.put(base + 23, probeBlend);

        this.staging.put(base + 24, lightBlock);
        this.staging.put(base + 25, lightSky);
        this.staging.put(base + 26, uvOffsetU);
        this.staging.put(base + 27, uvOffsetV);

        return index;
    }

    /** Uploads everything pushed since {@link #begin()}. No-op when nothing was pushed. */
    public void upload() {
        if (this.count == 0) {
            return;
        }
        ensureVbo();
        this.staging.position(0).limit(this.count * FLOATS_PER_INSTANCE);
        GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, this.vbo);
        // Orphan first so the driver does not have to wait on last frame's reads.
        glBufferData(GL_ARRAY_BUFFER, (long) this.count * BYTES_PER_INSTANCE, GL_STREAM_DRAW);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, this.staging);
        GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, 0);
        this.staging.limit(this.staging.capacity());
    }

    /**
     * Points the instance attributes of the currently bound VAO at this buffer, starting at
     * {@code firstInstance}.
     *
     * <p>Attribute pointers are VAO state, so this has to be redone whenever a batch's offset
     * changes. Seven calls per batch is nothing next to a draw call. On GL 4.2+ this could be
     * hoisted out entirely with {@code glDrawElementsInstancedBaseInstance}, which is worth
     * doing if batch counts ever reach the thousands.
     */
    public void bindAttributes(int firstInstance) {
        ensureVbo();
        GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, this.vbo);
        long base = (long) firstInstance * BYTES_PER_INSTANCE;

        for (int row = 0; row < 4; row++) {
            int loc = ATTRIB_MODEL + row;
            glEnableVertexAttribArray(loc);
            glVertexAttribPointer(loc, 4, GL_FLOAT, false, BYTES_PER_INSTANCE,
                    base + (long) row * 4 * Float.BYTES);
            glVertexAttribDivisor(loc, 1);
        }
        pointer(ATTRIB_TINT, base + 16L * Float.BYTES);
        pointer(ATTRIB_PARAMS, base + 20L * Float.BYTES);
        pointer(ATTRIB_LIGHT_UV, base + 24L * Float.BYTES);
    }

    private static void pointer(int location, long offset) {
        glEnableVertexAttribArray(location);
        glVertexAttribPointer(location, 4, GL_FLOAT, false, BYTES_PER_INSTANCE, offset);
        glVertexAttribDivisor(location, 1);
    }

    @Override
    public void close() {
        if (this.vbo != 0) {
            glDeleteBuffers(this.vbo);
            this.vbo = 0;
        }
        if (this.staging != null) {
            MemoryUtil.memFree(this.staging);
            this.staging = null;
        }
    }
}
