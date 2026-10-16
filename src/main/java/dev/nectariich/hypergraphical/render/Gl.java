package dev.nectariich.hypergraphical.render;

import dev.nectariich.hypergraphical.HgConfig;
import dev.nectariich.hypergraphical.Hypergraphical;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Driver capability probe and error checking.
 *
 * <p>Minecraft asks for a 3.2 core context, but Embeddium is a hard dependency of this mod
 * and Embeddium itself requires more than that, so GL 3.3 is a safe floor. That matters
 * because instanced arrays ({@code glVertexAttribDivisor}) are only core from 3.3 &mdash; they
 * are the whole basis of our batching.
 */
public final class Gl {

    private static boolean initialized;
    private static boolean instancedArrays;
    private static boolean seamlessCubemap;
    private static boolean baseInstance;
    private static boolean textureStorage;
    private static int maxTextureUnits;
    private static int maxCubemapSize;

    private Gl() {}

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        GLCapabilities caps = GL.getCapabilities();
        instancedArrays = caps.OpenGL33 || caps.GL_ARB_instanced_arrays;
        seamlessCubemap = caps.OpenGL32 || caps.GL_ARB_seamless_cube_map;
        baseInstance = caps.OpenGL42 || caps.GL_ARB_base_instance;
        textureStorage = caps.OpenGL42 || caps.GL_ARB_texture_storage;
        maxTextureUnits = glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        maxCubemapSize = glGetInteger(GL_MAX_CUBE_MAP_TEXTURE_SIZE);

        Hypergraphical.LOGGER.info(
                "[hypergraphical] GL caps: instancedArrays={} seamlessCubemap={} baseInstance={} "
                        + "textureStorage={} maxTextureUnits={} maxCubemapSize={}",
                instancedArrays, seamlessCubemap, baseInstance, textureStorage,
                maxTextureUnits, maxCubemapSize);

        if (!instancedArrays) {
            Hypergraphical.LOGGER.error("[hypergraphical] No instanced arrays support "
                    + "(GL 3.3 / ARB_instanced_arrays). The render pass is instanced-only, so "
                    + "nothing will be drawn. Embeddium requires more than this, so seeing "
                    + "this message means the context is not what it claims to be.");
        }
        if (seamlessCubemap) {
            // Without this, a rough reflection shows a hard seam along every cubemap edge
            // because the prefilter blur samples across faces.
            glEnable(0x884F); // GL_TEXTURE_CUBE_MAP_SEAMLESS
        }
    }

    public static boolean instancedArrays() {
        return instancedArrays;
    }

    public static boolean baseInstance() {
        return baseInstance;
    }

    public static int maxCubemapSize() {
        return maxCubemapSize <= 0 ? 1024 : maxCubemapSize;
    }

    /**
     * Drains and logs the GL error queue when {@code general.glDebugChecks} is on. This
     * forces a pipeline sync, so it is off by default and the call sites are cheap no-ops.
     */
    public static void check(String where) {
        if (!HgConfig.glDebug()) {
            return;
        }
        int error;
        while ((error = glGetError()) != GL_NO_ERROR) {
            Hypergraphical.LOGGER.error("[hypergraphical] GL error 0x{} at {}",
                    Integer.toHexString(error), where);
        }
    }

    /** Discards any pending GL errors so ours are not confused with someone else's. */
    public static void clearErrors() {
        while (glGetError() != GL_NO_ERROR) {
            // drain
        }
    }
}
