package rw.gov.risa.sso.wrapper.policy.audit;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which institution an admin action belongs to — for the audit trail only (pure parts of
 * {@code services/audit_scope.py}). Nothing here decides authorisation. All values are
 * lowercased (legacy UPPERCASE seeding lands in the same trail).
 */
public final class AuditScope {

    public static final String INSTITUTION_ROLE_PREFIX = "inst:";

    private AuditScope() {
    }

    /** The user's {@code institution} attribute, trimmed and lowercased; null when none. */
    public static String institutionOfUser(Map<String, List<String>> attributes) {
        if (attributes == null) {
            return null;
        }
        List<String> v = attributes.get("institution");
        String value = v == null || v.isEmpty() ? null : v.get(0);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

    /** {@code inst:minagri:admin -> minagri}; null outside the namespace. */
    public static String institutionOfRole(String roleName) {
        if (roleName == null || !roleName.startsWith(INSTITUTION_ROLE_PREFIX)) {
            return null;
        }
        String rest = roleName.substring(INSTITUTION_ROLE_PREFIX.length());
        int colon = rest.indexOf(':');
        String slug = (colon >= 0 ? rest.substring(0, colon) : rest).strip().toLowerCase(Locale.ROOT);
        return slug.isEmpty() ? null : slug;
    }

    /** The ONE institution all namespaced roles belong to; null when none or when they span two. */
    public static String institutionOfRoles(Collection<String> roleNames) {
        Set<String> slugs = new HashSet<>();
        for (String r : roleNames) {
            String s = institutionOfRole(r);
            if (s != null) {
                slugs.add(s);
            }
        }
        return slugs.size() == 1 ? slugs.iterator().next() : null;
    }

    /**
     * The institution whose top-level group this group is or sits under: the path's first
     * segment (or {@code /<name>}), checked against the known slugs.
     */
    public static String institutionOfGroup(String path, String name, Set<String> knownSlugs) {
        String p = path != null && !path.isEmpty() ? path : "/" + (name == null ? "" : name);
        String stripped = p.replaceAll("^/+|/+$", "");
        int slash = stripped.indexOf('/');
        String first = (slash >= 0 ? stripped.substring(0, slash) : stripped).strip().toLowerCase(Locale.ROOT);
        return !first.isEmpty() && knownSlugs != null && knownSlugs.contains(first) ? first : null;
    }

    /** {@code {"institution": slug}} or an empty map — an untagged event stays untagged. */
    public static Map<String, Object> institutionField(String institution) {
        return institution == null || institution.isEmpty() ? Map.of() : Map.of("institution", institution);
    }
}
