package rw.gov.risa.sso.wrapper.policy.delegated;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin file loader for {@code configs/delegated-permissions/templates.json}. Parsing and
 * validation live in {@link DelegatedPermissionsParser}; this class only reads bytes and
 * maps IO / JSON errors onto {@link DelegatedPermissionConfigException} (fail closed).
 *
 * <p>Caching and hot reload ({@code POST /delegated-permissions/reload}: parse first, swap
 * only on success) are the service layer's job.
 */
public final class DelegatedPermissionsLoader {

    /** Default {@code DELEGATED_PERMISSIONS_CONFIG}, relative to the working directory. */
    public static final String DEFAULT_CONFIG = "configs/delegated-permissions/templates.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DelegatedPermissionsLoader() {
    }

    /** {@code load_registry_from(path)} — parse and validate one file (no caching). */
    public static DelegatedPermissionRegistry loadFrom(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DelegatedPermissionConfigException("cannot read " + path + ": " + e, e);
        }
        return parse(text, path.toString());
    }

    /** Parse JSON text. */
    public static DelegatedPermissionRegistry parse(String json, String source) {
        Object raw;
        try {
            raw = MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new DelegatedPermissionConfigException(source + " is not valid JSON: " + e.getOriginalMessage(), e);
        }
        return DelegatedPermissionsParser.fromMap(raw, source);
    }

    /**
     * Candidate locations for a configured path ({@code _candidate_paths}): an absolute path
     * as-is; a relative one against the working directory, then each extra base directory.
     */
    public static List<Path> candidatePaths(String configured, List<Path> extraBases) {
        Path p = Path.of(configured);
        if (p.isAbsolute()) {
            return List.of(p);
        }
        List<Path> out = new ArrayList<>();
        out.add(Path.of("").toAbsolutePath().resolve(p));
        for (Path base : extraBases) {
            out.add(base.resolve(p));
        }
        return out;
    }

    /** {@code config_path()} — the first existing candidate, or throw listing all of them. */
    public static Path locate(String configured, List<Path> extraBases) {
        List<Path> candidates = candidatePaths(configured, extraBases);
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) {
                return c;
            }
        }
        StringBuilder looked = new StringBuilder();
        for (Path c : candidates) {
            if (!looked.isEmpty()) {
                looked.append(", ");
            }
            looked.append(c);
        }
        throw new DelegatedPermissionConfigException("delegated-permission template file not found. Looked in: "
                + looked + ". Set DELEGATED_PERMISSIONS_CONFIG to its location.");
    }

    /** Convenience for tests and tooling: parse an already-decoded tree. */
    public static DelegatedPermissionRegistry fromMap(Map<String, Object> raw, String source) {
        return DelegatedPermissionsParser.fromMap(raw, source);
    }
}
