package rw.gov.risa.sso.wrapper.policy.registry;

import java.util.List;

/** Validator output: errors fail the gate; warnings fail it only under {@code --strict}. */
public record RegistryFindings(List<String> errors, List<String> warnings) {

    public RegistryFindings {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public boolean isClean() {
        return errors.isEmpty();
    }

    public boolean isClean(boolean strict) {
        return errors.isEmpty() && (!strict || warnings.isEmpty());
    }

    /** Process exit code as the reference script uses it: 0 clean, 1 findings. */
    public int exitCode(boolean strict) {
        return isClean(strict) ? 0 : 1;
    }
}
