package rw.gov.risa.sso.wrapper.policy.delegated;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The namespace conventions every delegated rule rests on (ADR-0001 §5): a canonical
 * lowercase slug, realm roles {@code inst:<slug>:<role>}, clients {@code <slug>-<app>},
 * the institution group {@code /<slug>}.
 */
public final class DelegatedNamespaces {

    /** Canonical slug charset — lowercase, digits, hyphen. */
    public static final String SLUG_CHARS = "[a-z0-9-]+";
    public static final Pattern SLUG = Pattern.compile("\\A" + SLUG_CHARS + "\\z");

    public static final String SLUG_PLACEHOLDER = "{slug}";
    /** Template-side namespaces a template may never write outside of. */
    public static final String ROLE_NAMESPACE = "inst:" + SLUG_PLACEHOLDER + ":";
    public static final String CLIENT_NAMESPACE = SLUG_PLACEHOLDER + "-";
    public static final String GROUP_ROOT = "/" + SLUG_PLACEHOLDER;

    private static final Pattern INSTITUTION_ROLE =
            Pattern.compile("\\Ainst:(" + SLUG_CHARS + "):([^:]+)\\z");

    private DelegatedNamespaces() {
    }

    /** {@code [a-z0-9-]+}, nothing else (null/empty is invalid). */
    public static boolean isValidSlug(String slug) {
        return slug != null && SLUG.matcher(slug).matches();
    }

    /** {@code inst:<slug>:} */
    public static String rolePrefix(String slug) {
        return "inst:" + slug + ":";
    }

    /** {@code <slug>-} */
    public static String clientPrefix(String slug) {
        return slug + "-";
    }

    /** {@code /<slug>} */
    public static String groupPath(String slug) {
        return "/" + slug;
    }

    /** Substitute every {@code {slug}} placeholder. */
    public static String render(String pattern, String slug) {
        return pattern.replace(SLUG_PLACEHOLDER, slug);
    }

    /** A realm role of the form {@code inst:<slug>:<short>} (short name contains no ':'). */
    public record InstitutionRole(String slug, String shortName) {
        public String roleName() {
            return rolePrefix(slug) + shortName;
        }
    }

    /** Parse {@code inst:<slug>:<short>}; empty for anything else. */
    public static Optional<InstitutionRole> parseInstitutionRole(String role) {
        if (role == null) {
            return Optional.empty();
        }
        Matcher m = INSTITUTION_ROLE.matcher(role);
        return m.matches() ? Optional.of(new InstitutionRole(m.group(1), m.group(2))) : Optional.empty();
    }
}
