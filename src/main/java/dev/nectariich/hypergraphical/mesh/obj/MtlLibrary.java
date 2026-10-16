package dev.nectariich.hypergraphical.mesh.obj;

import dev.nectariich.hypergraphical.Hypergraphical;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A parsed {@code .mtl} library.
 *
 * <p>Alongside the classic Wavefront fields this understands the widely adopted PBR
 * extensions that Blender, Substance and Maya all write, so a normal export already carries
 * usable metallic/roughness values:
 *
 * <pre>
 *   map_Kd   albedo texture      Kd   albedo tint
 *   map_Ke   emissive texture    Ke   emissive colour
 *   map_Pm   metallic texture    Pm   metallic scalar
 *   map_Pr   roughness texture   Pr   roughness scalar
 *   norm / map_Bump / bump       normal map  (parsed and stored; not sampled yet)
 *   Ns                           specular exponent, used only if Pr is absent
 *   d / Tr                       opacity
 * </pre>
 *
 * <p>When {@code Pr} is missing, roughness is derived from the Phong exponent {@code Ns} with
 * the usual {@code sqrt(2 / (Ns + 2))} mapping. That is not physically rigorous, but it turns
 * a legacy Phong export into something that at least reads as "shiny" versus "matte" instead
 * of defaulting everything to mirror-smooth.
 */
public final class MtlLibrary {

    /** One {@code newmtl} block. */
    public static final class Entry {
        public final String name;
        public float[] albedoTint = {1.0F, 1.0F, 1.0F};
        public float[] emissiveTint = {0.0F, 0.0F, 0.0F};
        public float alpha = 1.0F;
        public float metallic = 0.0F;
        public float roughness = 0.5F;
        public boolean roughnessFromNs;
        @Nullable public ResourceLocation albedoMap;
        @Nullable public ResourceLocation emissiveMap;
        @Nullable public ResourceLocation metallicMap;
        @Nullable public ResourceLocation roughnessMap;
        /** Parsed for forward compatibility; normal mapping is not implemented yet. */
        @Nullable public ResourceLocation normalMap;

        Entry(String name) {
            this.name = name;
        }

        public float smoothness() {
            return 1.0F - Math.min(1.0F, Math.max(0.0F, this.roughness));
        }
    }

    private final Map<String, Entry> entries = new HashMap<>();

    public static MtlLibrary empty() {
        return new MtlLibrary();
    }

    @Nullable
    public Entry get(String name) {
        return this.entries.get(name);
    }

    public Map<String, Entry> entries() {
        return this.entries;
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    /**
     * Parses an MTL stream. Unknown statements are skipped silently, which is deliberate:
     * exporters emit a long tail of vendor keywords and refusing to load a model because of
     * an unrecognised {@code Pc} line would be hostile.
     */
    public static MtlLibrary parse(InputStream in, ResourceLocation mtlLocation) throws IOException {
        MtlLibrary lib = new MtlLibrary();
        Entry current = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = stripComment(line);
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                String key = parts[0].toLowerCase(Locale.ROOT);

                switch (key) {
                    case "newmtl" -> {
                        String name = parts.length > 1 ? join(parts, 1) : "default";
                        current = new Entry(name);
                        lib.entries.put(name, current);
                    }
                    case "kd" -> {
                        if (current != null) current.albedoTint = vec3(parts, 1.0F);
                    }
                    case "ke" -> {
                        if (current != null) current.emissiveTint = vec3(parts, 0.0F);
                    }
                    case "d" -> {
                        if (current != null) current.alpha = f(parts, 1, 1.0F);
                    }
                    case "tr" -> {
                        // Tr is transparency; the inverse of d.
                        if (current != null) current.alpha = 1.0F - f(parts, 1, 0.0F);
                    }
                    case "pm" -> {
                        if (current != null) current.metallic = clamp01(f(parts, 1, 0.0F));
                    }
                    case "pr" -> {
                        if (current != null) {
                            current.roughness = clamp01(f(parts, 1, 0.5F));
                            current.roughnessFromNs = false;
                        }
                    }
                    case "ns" -> {
                        if (current != null && current.roughness == 0.5F && !hasExplicitRoughness(current)) {
                            float ns = Math.max(0.0F, f(parts, 1, 0.0F));
                            current.roughness = clamp01((float) Math.sqrt(2.0D / (ns + 2.0D)));
                            current.roughnessFromNs = true;
                        }
                    }
                    case "map_kd" -> {
                        if (current != null) current.albedoMap = texturePath(parts, mtlLocation);
                    }
                    case "map_ke", "map_emissive" -> {
                        if (current != null) current.emissiveMap = texturePath(parts, mtlLocation);
                    }
                    case "map_pm", "map_metallic" -> {
                        if (current != null) current.metallicMap = texturePath(parts, mtlLocation);
                    }
                    case "map_pr", "map_roughness" -> {
                        if (current != null) current.roughnessMap = texturePath(parts, mtlLocation);
                    }
                    case "norm", "map_bump", "bump", "map_normal" -> {
                        if (current != null) current.normalMap = texturePath(parts, mtlLocation);
                    }
                    default -> {
                        // ignored
                    }
                }
            }
        }
        return lib;
    }

    private static boolean hasExplicitRoughness(Entry e) {
        return !e.roughnessFromNs && e.roughnessMap != null;
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        if (hash >= 0) {
            line = line.substring(0, hash);
        }
        return line.trim();
    }

    private static String join(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static float[] vec3(String[] parts, float fallback) {
        return new float[] {f(parts, 1, fallback), f(parts, 2, fallback), f(parts, 3, fallback)};
    }

    private static float f(String[] parts, int index, float fallback) {
        if (index >= parts.length) {
            return fallback;
        }
        try {
            return Float.parseFloat(parts[index]);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }

    /**
     * Extracts the filename from a map statement, skipping the {@code -blendu}/{@code -s}
     * style options that exporters put in front of it, then resolves it to a
     * {@link ResourceLocation}.
     */
    @Nullable
    private static ResourceLocation texturePath(String[] parts, ResourceLocation mtlLocation) {
        int i = 1;
        while (i < parts.length && parts[i].startsWith("-")) {
            // Options take a varying number of arguments. Skip the flag plus every following
            // token that parses as a number, which covers -s/-o/-t/-mm and friends.
            i++;
            while (i < parts.length && isNumeric(parts[i])) {
                i++;
            }
        }
        if (i >= parts.length) {
            return null;
        }
        return resolveRelative(mtlLocation, join(parts, i));
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) {
            return false;
        }
        try {
            Float.parseFloat(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Resolves an OBJ/MTL relative path against the location of the file that referenced it.
     *
     * <p>Accepts three forms: a fully qualified {@code namespace:path}, a namespace-absolute
     * {@code /path}, or a relative {@code ../textures/foo.png}. Backslashes are normalised,
     * because plenty of MTL files in the wild were written on Windows.
     */
    public static ResourceLocation resolveRelative(ResourceLocation base, String raw) {
        String path = raw.trim().replace('\\', '/');
        if (path.isEmpty()) {
            return Hypergraphical.id("missing");
        }
        if (path.indexOf(':') >= 0) {
            try {
                return new ResourceLocation(path);
            } catch (Exception e) {
                Hypergraphical.LOGGER.warn("[hypergraphical] Bad resource path '{}' in {}", raw, base);
                return Hypergraphical.id("missing");
            }
        }

        Deque<String> stack = new ArrayDeque<>();
        if (!path.startsWith("/")) {
            String basePath = base.getPath();
            int slash = basePath.lastIndexOf('/');
            if (slash > 0) {
                for (String segment : basePath.substring(0, slash).split("/")) {
                    if (!segment.isEmpty()) {
                        stack.addLast(segment);
                    }
                }
            }
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                stack.pollLast();
            } else {
                stack.addLast(segment);
            }
        }
        return new ResourceLocation(base.getNamespace(), String.join("/", stack));
    }
}
