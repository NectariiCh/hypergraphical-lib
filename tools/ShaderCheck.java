import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Offline validator for the mod's GLSL.
 *
 * <p>Creates a hidden GLFW window with a GL 3.3 core context, then compiles and links every
 * program exactly as {@code ShaderProgram} does at runtime, including {@code #hg_include}
 * resolution and the injected {@code #define}s. A driver rejecting our GLSL is otherwise only
 * discoverable by launching the game and reading the log, which is a slow way to find a typo.
 *
 * <p>The preprocessing here is a deliberate duplicate of the twenty lines in
 * {@code ShaderProgram#preprocess}. Reusing the real one would mean dragging in a Minecraft
 * {@code ResourceManager} and therefore the whole game. If the include or define rules change,
 * change both.
 *
 * <p>Not part of the mod jar. Run it through {@code tools/check-shaders.sh}.
 */
public final class ShaderCheck {

    private static final int MAX_INCLUDE_DEPTH = 8;

    private static Path root;
    private static int failures;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: ShaderCheck <assets-root>");
            System.exit(2);
        }
        root = Path.of(args[0]);

        GLFW.glfwSetErrorCallback((error, description) ->
                System.err.println("GLFW error " + error));
        if (!GLFW.glfwInit()) {
            System.err.println("Could not initialise GLFW; skipping shader validation.");
            System.exit(3);
        }

        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);

        long window = GLFW.glfwCreateWindow(64, 64, "hypergraphical shader check", 0L, 0L);
        if (window == 0L) {
            System.err.println("Could not create a GL 3.3 core context; skipping validation.");
            GLFW.glfwTerminate();
            System.exit(3);
        }
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        System.out.println("GL_VERSION  " + glGetString(GL_VERSION));
        System.out.println("GL_RENDERER " + glGetString(GL_RENDERER));
        System.out.println();

        check("pbr", "shaders/hg/pbr.vsh", "shaders/hg/pbr.fsh", Map.of());
        check("brdf_lut", "shaders/hg/fullscreen.vsh", "shaders/hg/brdf_lut.fsh", Map.of());
        check("irradiance", "shaders/hg/fullscreen.vsh", "shaders/hg/irradiance.fsh",
                Map.of("HG_SAMPLES", "128"));
        check("prefilter", "shaders/hg/fullscreen.vsh", "shaders/hg/prefilter.fsh",
                Map.of("HG_SAMPLES", "64"));
        check("environment", "shaders/hg/fullscreen.vsh", "shaders/hg/environment.fsh", Map.of());

        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();

        System.out.println();
        if (failures == 0) {
            System.out.println("All shader programs compiled and linked.");
        } else {
            System.out.println(failures + " program(s) failed.");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String name, String vertex, String fragment,
                              Map<String, String> defines) throws IOException {
        String vertexSource = preprocess(vertex, defines, 0);
        String fragmentSource = preprocess(fragment, defines, 0);

        int vs = compile(GL_VERTEX_SHADER, vertexSource, name + ":" + vertex);
        int fs = compile(GL_FRAGMENT_SHADER, fragmentSource, name + ":" + fragment);
        if (vs == 0 || fs == 0) {
            failures++;
            return;
        }

        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);

        boolean linked = glGetProgrami(program, GL_LINK_STATUS) != GL_FALSE;
        if (!linked) {
            System.out.println("FAIL link " + name);
            System.out.println(indent(glGetProgramInfoLog(program)));
            failures++;
        } else {
            System.out.printf("ok   %-12s  %d active uniform(s), %d active attribute(s)%n", name,
                    glGetProgrami(program, GL_ACTIVE_UNIFORMS),
                    glGetProgrami(program, GL_ACTIVE_ATTRIBUTES));
            reportUniforms(program);
        }

        glDeleteShader(vs);
        glDeleteShader(fs);
        glDeleteProgram(program);
    }

    /** Lists active uniforms, which is how a silently stripped uniform gets noticed. */
    private static void reportUniforms(int program) {
        int count = glGetProgrami(program, GL_ACTIVE_UNIFORMS);
        StringBuilder names = new StringBuilder();
        java.nio.IntBuffer size = org.lwjgl.BufferUtils.createIntBuffer(1);
        java.nio.IntBuffer type = org.lwjgl.BufferUtils.createIntBuffer(1);
        for (int i = 0; i < count; i++) {
            String uniform = glGetActiveUniform(program, i, size, type);
            if (!names.isEmpty()) {
                names.append(' ');
            }
            names.append(uniform);
        }
        if (count > 0) {
            System.out.println("       " + wrap(names.toString(), 96));
        }
    }

    private static int compile(int type, String source, String label) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            System.out.println("FAIL " + label);
            System.out.println(indent(glGetShaderInfoLog(shader)));
            System.out.println(numberLines(source));
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    // ---- mirrors ShaderProgram#preprocess ----------------------------------
    private static String preprocess(String relative, Map<String, String> defines, int depth)
            throws IOException {
        if (depth > MAX_INCLUDE_DEPTH) {
            throw new IOException("#hg_include too deep in " + relative);
        }
        String raw = Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(raw.length() + 512);
        boolean injected = depth > 0;

        for (String line : raw.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#hg_include")) {
                int first = trimmed.indexOf('"');
                int last = trimmed.lastIndexOf('"');
                String included = trimmed.substring(first + 1, last);
                // "hypergraphical:shaders/hg/x.glsl" -> "shaders/hg/x.glsl"
                int colon = included.indexOf(':');
                out.append(preprocess(included.substring(colon + 1), defines, depth + 1));
                out.append('\n');
                continue;
            }
            out.append(line).append('\n');
            if (!injected && trimmed.startsWith("#version")) {
                for (Map.Entry<String, String> define : new LinkedHashMap<>(defines).entrySet()) {
                    out.append("#define ").append(define.getKey()).append(' ')
                            .append(define.getValue()).append('\n');
                }
                injected = true;
            }
        }
        return out.toString();
    }

    private static String indent(String text) {
        return text.strip().lines().map(line -> "       " + line)
                .reduce((a, b) -> a + System.lineSeparator() + b).orElse("");
    }

    private static String numberLines(String source) {
        String[] lines = source.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%5d | %s%n", i + 1, lines[i]));
        }
        return sb.toString();
    }

    private static String wrap(String text, int width) {
        StringBuilder sb = new StringBuilder();
        int column = 0;
        for (String word : text.split(" ")) {
            if (column + word.length() > width) {
                sb.append("\n       ");
                column = 0;
            }
            sb.append(word).append(' ');
            column += word.length() + 1;
        }
        return sb.toString().strip();
    }
}
