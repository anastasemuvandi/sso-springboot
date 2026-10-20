package rw.gov.risa.sso.wrapper.cli;

import java.nio.file.Files;
import java.nio.file.Path;

import rw.gov.risa.sso.wrapper.service.institution.ConfigPaths;

/**
 * Where an ops command finds a config file: an explicit {@code --option} path as given, else the
 * configured/default relative path resolved like the running wrapper does (working directory, its parent
 * — the repo root when started from {@code sso-wrapper/} — then {@code /app}).
 */
public final class ConfigFiles {

    private ConfigFiles() {
    }

    /** The file, or null when it cannot be found. */
    public static Path resolve(String explicit, String configured) {
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit);
            return Files.exists(p) ? p : null;
        }
        return ConfigPaths.locate(configured).orElse(null);
    }

    /** The repo root a {@code configs/...} file sits under ({@code configs/a/b.json} → its grand-grandparent). */
    public static Path repoRootOf(Path configFile, String relative) {
        Path root = configFile.toAbsolutePath();
        for (int i = 0; i < Path.of(relative).getNameCount(); i++) {
            root = root.getParent();
        }
        return root;
    }
}
