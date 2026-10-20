package rw.gov.risa.sso.wrapper.policy.registry;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ADR-0001 §5.1 slug rules: lowercase, {@code [a-z0-9-]}, starts alphanumeric. Underscores,
 * dots, spaces and colons are excluded so {@code inst:<slug>:<role>} stays unambiguous.
 */
public final class Slugs {

    public static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    public static final Pattern APP_NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private Slugs() {
    }

    public static boolean isValidSlug(String slug) {
        return slug != null && SLUG_PATTERN.matcher(slug).matches();
    }

    public static boolean isValidAppName(String name) {
        return name != null && APP_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Legacy normalisation: pre-ADR-0001 seeding left UPPERCASE institution values
     * ({@code MINAGRI}); the canonical form is the trimmed, lower-cased value.
     * Returns {@code null} for null/blank input.
     */
    public static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String t = value.strip();
        return t.isEmpty() ? null : t.toLowerCase(Locale.ROOT);
    }

    /**
     * True when {@code inner} extends {@code outer} with a hyphen ({@code local} vs
     * {@code local-govt}) — forbidden because client scoping is the glob {@code <slug>-*}.
     */
    public static boolean isHyphenExtension(String outer, String inner) {
        return outer != null && inner != null && !inner.equals(outer) && inner.startsWith(outer + "-");
    }
}
