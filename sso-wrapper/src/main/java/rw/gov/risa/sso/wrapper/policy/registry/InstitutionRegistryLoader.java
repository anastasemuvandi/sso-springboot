package rw.gov.risa.sso.wrapper.policy.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Thin YAML loader for {@code configs/institutions.yaml}. All parsing lives here; the
 * decision logic in {@link InstitutionRegistry} / {@link RegistryValidator} operates on the
 * already-parsed tree (plain {@code Map}/{@code List}/scalars).
 */
public final class InstitutionRegistryLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private InstitutionRegistryLoader() {
    }

    /** Parse YAML text into a plain tree; an empty document yields {@code null}. */
    public static Object parseYaml(String text) throws IOException {
        if (text == null || text.isBlank()) {
            return null;
        }
        return YAML.readValue(text, Object.class);
    }

    /** Read and parse one file into a plain tree. */
    public static Object readRaw(Path path) throws IOException {
        return parseYaml(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** Parse one registry file (no caching). Throws on unreadable/invalid YAML. */
    public static InstitutionRegistry load(Path path) throws IOException {
        return InstitutionRegistry.fromRaw(readRaw(path), path.toString());
    }

    /**
     * The never-fails-closed variant used at runtime: a missing or unreadable file yields an
     * empty registry whose {@code source} records why ({@code <missing>} or the path).
     */
    public static InstitutionRegistry loadOrEmpty(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return InstitutionRegistry.empty("<missing>");
        }
        try {
            return load(path);
        } catch (IOException | RuntimeException e) {
            return InstitutionRegistry.empty(path.toString());
        }
    }

    /** First existing candidate path (mirrors the reference's cwd / repo / container lookup). */
    public static Optional<Path> firstExisting(List<Path> candidates) {
        return candidates.stream().filter(p -> p != null && Files.isRegularFile(p)).findFirst();
    }
}
