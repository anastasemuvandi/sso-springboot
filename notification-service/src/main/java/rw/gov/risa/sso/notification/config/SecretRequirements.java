package rw.gov.risa.sso.notification.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Staging/prod refuse to start with a missing or placeholder secret (spec §7.10). Pure so it
 * can be unit-tested; enforced at startup by {@link NotificationConfig}.
 */
public final class SecretRequirements {

    static final String PLACEHOLDER = "change-me-in-production";

    private SecretRequirements() {
    }

    /** Names of the secrets that are missing or still the placeholder. */
    public static List<String> missing(NotificationProperties p) {
        List<String> out = new ArrayList<>();
        if (bad(p.apiKey())) {
            out.add("NOTIFICATION_API_KEY");
        }
        if (p.audit() == null || bad(p.audit().apiKey())) {
            out.add("AUDIT_API_KEY");
        }
        if (p.smtp() != null && p.smtp().configured() && p.smtp().user() != null && !p.smtp().user().isBlank()
                && (p.smtp().password() == null || p.smtp().password().isBlank())) {
            out.add("SMTP_PASSWORD");
        }
        return out;
    }

    private static boolean bad(String s) {
        return s == null || s.isBlank() || PLACEHOLDER.equals(s);
    }
}
