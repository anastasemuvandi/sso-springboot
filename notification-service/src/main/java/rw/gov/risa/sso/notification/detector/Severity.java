package rw.gov.risa.sso.notification.detector;

import java.util.Locale;
import java.util.Optional;

/** Alert severities. Stored and sent on the wire as the upper-case name. */
public enum Severity {
    CRITICAL, WARNING, INFO;

    public static Optional<Severity> parse(String s) {
        if (s == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(s.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
