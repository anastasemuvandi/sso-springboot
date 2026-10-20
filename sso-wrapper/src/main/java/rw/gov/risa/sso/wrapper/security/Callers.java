package rw.gov.risa.sso.wrapper.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Static access to the current caller, for code paths without a controller parameter. */
public final class Callers {
    private Callers() {}

    public static Optional<CallerPrincipal> current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof CallerPrincipal p) {
            return Optional.of(p);
        }
        return Optional.empty();
    }
}
