package rw.gov.risa.sso.wrapper.policy.delegated;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the repository's {@code configs/} directory from a test, wherever the build runs. */
public final class TestRepo {

    private static final String KNOWN_ROOT = "/home/imfura/Data/CURIE_RISA/sso-spring-react/codebase";

    private TestRepo() {
    }

    public static Path root() {
        String prop = System.getProperty("gorsso.repo.root");
        if (prop != null && Files.isDirectory(Path.of(prop, "configs"))) {
            return Path.of(prop);
        }
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("configs/delegated-permissions/templates.json"))) {
                return p;
            }
        }
        return Path.of(KNOWN_ROOT);
    }

    public static Path path(String relative) {
        return root().resolve(relative);
    }
}
