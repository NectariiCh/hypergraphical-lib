package dev.nectariich.hypergraphical.mesh.obj;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

/**
 * Wavefront line lexer.
 *
 * <p>This mirrors the tokenisation rules of Forge's own {@code forge:obj} loader
 * ({@code net.minecraftforge.client.model.obj.ObjTokenizer}) so that an asset behaves
 * identically whether Forge bakes it for the vanilla block pipeline or we import it for the
 * PBR pass: {@code #} starts a comment, a trailing backslash continues the logical line, and
 * runs of whitespace collapse.
 *
 * <p>It is reimplemented here rather than delegated to, for one reason: Forge's tokenizer is
 * package-coupled to {@code ObjModel}, whose parsed geometry is only reachable by baking to
 * {@code BakedQuad}s. That bake is exactly what we must avoid, because it quantises normals
 * to bytes and discards tangents. Sharing 40 lines of lexing is not worth taking a hard
 * dependency on internals that would break the mesh path on a Forge patch bump.
 */
final class ObjTokenSource implements AutoCloseable {

    private final BufferedReader reader;

    ObjTokenSource(InputStream in) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /**
     * Next logical line split on whitespace, or {@code null} at end of stream. Blank and
     * comment-only lines are skipped, so callers never see an empty array.
     */
    @Nullable
    String[] nextLine() throws IOException {
        StringBuilder logical = new StringBuilder();
        String raw;
        while ((raw = this.reader.readLine()) != null) {
            int hash = raw.indexOf('#');
            if (hash >= 0) {
                raw = raw.substring(0, hash);
            }
            raw = raw.trim();
            if (raw.endsWith("\\")) {
                logical.append(raw, 0, raw.length() - 1).append(' ');
                continue;
            }
            logical.append(raw);
            String line = logical.toString().trim();
            if (line.isEmpty()) {
                logical.setLength(0);
                continue;
            }
            return line.split("\\s+");
        }
        String tail = logical.toString().trim();
        return tail.isEmpty() ? null : tail.split("\\s+");
    }

    @Override
    public void close() throws IOException {
        this.reader.close();
    }
}
