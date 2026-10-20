package rw.gov.risa.sso.wrapper.policy.registry;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates committed repo files from tests: walk up from user.dir, else the known checkout. */
final class RepoFiles {
    private static final Path FALLBACK = Path.of("/home/imfura/Data/CURIE_RISA/sso-spring-react/codebase");

    private RepoFiles() {
    }

    static Path find(String relative) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return FALLBACK.resolve(relative);
    }
}
