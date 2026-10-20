package rw.gov.risa.sso.wrapper.service.institution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.registry.InstitutionRegistryLoader;

/**
 * Where a configured config path is looked for (the reference's {@code _candidate_paths}): an absolute
 * path as-is; a relative one against the working directory, then the repo root (the working
 * directory's parent — the wrapper is usually started from {@code sso-wrapper/}), then {@code /app}
 * (the container's WORKDIR).
 */
public final class ConfigPaths {

    private ConfigPaths() {
    }

    public static List<Path> extraBases() {
        List<Path> out = new ArrayList<>();
        Path cwd = Path.of("").toAbsolutePath();
        if (cwd.getParent() != null) {
            out.add(cwd.getParent());
        }
        out.add(Path.of("/app"));
        return out;
    }

    public static List<Path> candidates(String configured) {
        return DelegatedPermissionsLoader.candidatePaths(configured, extraBases());
    }

    /** First existing candidate. */
    public static Optional<Path> locate(String configured) {
        return InstitutionRegistryLoader.firstExisting(candidates(configured));
    }
}
