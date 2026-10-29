package dev.nectariich.hypergraphical.render;

import dev.nectariich.hypergraphical.Hypergraphical;
import dev.nectariich.hypergraphical.util.HgIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.lwjgl.opengl.GL33C.*;

/**
 * A linked GLSL program, loaded from the resource pack so it hot-reloads on F3+T.
 *
 * <p>Deliberately raw GL rather than Minecraft's {@code ShaderInstance}: that class hard-codes
 * vanilla's uniform set and vertex formats and assumes the immediate-mode
 * {@code BufferBuilder} path. Neither fits an instanced PBR pass.
 *
 * <p>Supports a one-line include directive so the BRDF and the probe sampling code can be
 * shared between the main pass and the IBL bake passes without copy-paste:
 *
 * <pre>#hg_include "hypergraphical:shaders/hg/pbr_lib.glsl"</pre>
 *
 * <p>Uniform locations are resolved once at link time and cached; scalar and vector uniform
 * values are cached too, so re-setting an unchanged material value costs a float compare
 * instead of a driver call. Over a few hundred batches that is a measurable slice of frame
 * time on drivers where {@code glUniform} is not free.
 */
public final class ShaderProgram implements AutoCloseable {

    private static final int MAX_INCLUDE_DEPTH = 8;

    private final String name;
    private final ResourceLocation vertexSource;
    private final ResourceLocation fragmentSource;

    private int program;
    private final Map<String, Integer> uniforms = new HashMap<>();
    private final Map<Integer, float[]> cachedFloats = new HashMap<>();
    private final Map<Integer, Integer> cachedInts = new HashMap<>();
    private final Set<String> missingWarned = new HashSet<>();

    public ShaderProgram(String name, ResourceLocation vertexSource, ResourceLocation fragmentSource) {
        this.name = name;
        this.vertexSource = vertexSource;
        this.fragmentSource = fragmentSource;
    }

    public String name() {
        return this.name;
    }

    public boolean valid() {
        return this.program != 0;
    }

    public int handle() {
        return this.program;
    }

    /**
     * Compiles and links, replacing any previous program. On failure the old program is kept
     * so a typo during hot-reload does not leave the scene invisible.
     *
     * @param defines extra {@code #define} lines injected after the version directive
     */
    public boolean compile(ResourceManager resources, Map<String, String> defines) {
        int vs = 0;
        int fs = 0;
        int linked = 0;
        try {
            String vsSource = preprocess(resources, this.vertexSource, defines, 0);
            String fsSource = preprocess(resources, this.fragmentSource, defines, 0);

            vs = compileStage(GL_VERTEX_SHADER, vsSource, this.vertexSource);
            fs = compileStage(GL_FRAGMENT_SHADER, fsSource, this.fragmentSource);
            if (vs == 0 || fs == 0) {
                return false;
            }

            linked = glCreateProgram();
            glAttachShader(linked, vs);
            glAttachShader(linked, fs);
            glLinkProgram(linked);
            if (glGetProgrami(linked, GL_LINK_STATUS) == GL_FALSE) {
                Hypergraphical.LOGGER.error("[hypergraphical] Failed to link shader '{}':\n{}",
                        this.name, glGetProgramInfoLog(linked));
                glDeleteProgram(linked);
                return false;
            }

            if (this.program != 0) {
                glDeleteProgram(this.program);
            }
            this.program = linked;
            linked = 0;
            this.uniforms.clear();
            this.cachedFloats.clear();
            this.cachedInts.clear();
            this.missingWarned.clear();
            Hypergraphical.LOGGER.debug("[hypergraphical] Compiled shader '{}'", this.name);
            return true;
        } catch (IOException e) {
            Hypergraphical.LOGGER.error("[hypergraphical] Missing shader source for '{}': {}",
                    this.name, e.toString());
            return false;
        } finally {
            if (vs != 0) glDeleteShader(vs);
            if (fs != 0) glDeleteShader(fs);
            if (linked != 0) glDeleteProgram(linked);
        }
    }

    private int compileStage(int type, String source, ResourceLocation origin) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            Hypergraphical.LOGGER.error("[hypergraphical] Failed to compile {} ({}):\n{}",
                    origin, type == GL_VERTEX_SHADER ? "vertex" : "fragment", glGetShaderInfoLog(shader));
            Hypergraphical.LOGGER.error("[hypergraphical] Source was:\n{}", numberLines(source));
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static String numberLines(String source) {
        String[] lines = source.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%4d | %s%n", i + 1, lines[i]));
        }
        return sb.toString();
    }

    /**
     * Resolves {@code #hg_include} directives and injects {@code defines} directly after the
     * {@code #version} line, which must stay first in the file.
     */
    private String preprocess(ResourceManager resources, ResourceLocation location,
                              Map<String, String> defines, int depth) throws IOException {
        if (depth > MAX_INCLUDE_DEPTH) {
            throw new IOException("#hg_include nested deeper than " + MAX_INCLUDE_DEPTH + " in " + location);
        }
        String raw = read(resources, location);
        StringBuilder sb = new StringBuilder(raw.length() + 512);
        boolean injected = depth > 0;

        for (String line : raw.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#hg_include")) {
                int first = trimmed.indexOf('"');
                int last = trimmed.lastIndexOf('"');
                if (first < 0 || last <= first) {
                    throw new IOException("Malformed #hg_include in " + location + ": " + trimmed);
                }
                ResourceLocation included = HgIds.parse(trimmed.substring(first + 1, last));
                sb.append("// >>> ").append(included).append('\n');
                sb.append(preprocess(resources, included, defines, depth + 1));
                sb.append("\n// <<< ").append(included).append('\n');
                continue;
            }

            sb.append(line).append('\n');
            if (!injected && trimmed.startsWith("#version")) {
                for (Map.Entry<String, String> define : defines.entrySet()) {
                    sb.append("#define ").append(define.getKey()).append(' ')
                            .append(define.getValue()).append('\n');
                }
                injected = true;
            }
        }
        return sb.toString();
    }

    private static String read(ResourceManager resources, ResourceLocation location) throws IOException {
        Optional<net.minecraft.server.packs.resources.Resource> resource = resources.getResource(location);
        if (resource.isEmpty()) {
            throw new IOException("Shader resource not found: " + location);
        }
        try (InputStream in = resource.get().open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ binding
    public void bind() {
        glUseProgram(this.program);
    }

    public static void unbind() {
        glUseProgram(0);
    }

    // ----------------------------------------------------------------- uniforms
    private int location(String name) {
        Integer cached = this.uniforms.get(name);
        if (cached != null) {
            return cached;
        }
        int loc = glGetUniformLocation(this.program, name);
        this.uniforms.put(name, loc);
        if (loc < 0 && this.missingWarned.add(name)) {
            // Not an error: the compiler strips uniforms an active #define made unreachable.
            Hypergraphical.LOGGER.debug("[hypergraphical] Shader '{}' has no active uniform '{}'",
                    this.name, name);
        }
        return loc;
    }

    public void set(String name, int value) {
        int loc = location(name);
        if (loc < 0) {
            return;
        }
        Integer prev = this.cachedInts.get(loc);
        if (prev != null && prev == value) {
            return;
        }
        this.cachedInts.put(loc, value);
        glUniform1i(loc, value);
    }

    public void set(String name, boolean value) {
        set(name, value ? 1 : 0);
    }

    public void set(String name, float value) {
        int loc = location(name);
        if (loc < 0 || cached(loc, value)) {
            return;
        }
        glUniform1f(loc, value);
    }

    public void set(String name, float x, float y) {
        int loc = location(name);
        if (loc < 0 || cached(loc, x, y)) {
            return;
        }
        glUniform2f(loc, x, y);
    }

    public void set(String name, float x, float y, float z) {
        int loc = location(name);
        if (loc < 0 || cached(loc, x, y, z)) {
            return;
        }
        glUniform3f(loc, x, y, z);
    }

    public void set(String name, float x, float y, float z, float w) {
        int loc = location(name);
        if (loc < 0 || cached(loc, x, y, z, w)) {
            return;
        }
        glUniform4f(loc, x, y, z, w);
    }

    /** Matrices are not value-cached; the compare would cost more than the upload. */
    public void set(String name, Matrix4f matrix) {
        int loc = location(name);
        if (loc < 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            matrix.get(buffer);
            glUniformMatrix4fv(loc, false, buffer);
        }
    }

    private boolean cached(int loc, float... values) {
        float[] prev = this.cachedFloats.get(loc);
        if (prev != null && prev.length == values.length) {
            for (int i = 0; i < values.length; i++) {
                if (prev[i] != values[i]) {
                    System.arraycopy(values, 0, prev, 0, values.length);
                    return false;
                }
            }
            return true;
        }
        this.cachedFloats.put(loc, values.clone());
        return false;
    }

    /** Binds a sampler uniform to a texture unit. Safe to call every frame; it is cached. */
    public void sampler(String name, int unit) {
        set(name, unit);
    }

    @Override
    public void close() {
        if (this.program != 0) {
            glDeleteProgram(this.program);
            this.program = 0;
        }
        this.uniforms.clear();
        this.cachedFloats.clear();
        this.cachedInts.clear();
    }

    @Nullable
    public static ShaderProgram compileOrNull(ResourceManager resources, String name,
                                              ResourceLocation vs, ResourceLocation fs,
                                              Map<String, String> defines) {
        ShaderProgram program = new ShaderProgram(name, vs, fs);
        return program.compile(resources, defines) ? program : null;
    }
}
