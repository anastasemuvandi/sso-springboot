package rw.gov.risa.sso.wrapper.policy.clientscope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Constants and free functions of the client-scope policy (port of {@code client_scope.py}).
 *
 * <p>Pure: no Spring, no Keycloak, no HTTP. Inputs are plain client attribute maps (Keycloak's
 * {@code ClientRepresentation#getAttributes()} is a {@code Map<String,String>}; list-valued maps
 * are tolerated too, first value wins).
 */
public final class ClientScopes {

    /** ADR-0001 §5.1 — canonical slug charset. */
    public static final Pattern SLUG = Pattern.compile("^[a-z0-9-]+$");

    public static final String ATTR_OWNER = "sso.client.owner_institution";
    public static final String ATTR_CROSS_CUTTING = "sso.client.cross_cutting";
    public static final String ATTR_INSTITUTIONS = "sso.client.institutions";

    /**
     * Every attribute this module owns. The generic client-update endpoint refuses these keys so a
     * scope change can never arrive unaudited through a PUT body.
     */
    public static final List<String> SCOPE_ATTRIBUTES = List.of(ATTR_OWNER, ATTR_CROSS_CUTTING, ATTR_INSTITUTIONS);

    /** {@code sso.client.institutions} value meaning "every institution, including later ones". */
    public static final String ALL_INSTITUTIONS = "*";

    /** Keycloak's own list separator inside one attribute value. */
    public static final String SEPARATOR = "##";

    private static final Set<String> TRUTHY = Set.of("true", "1", "yes", "on");

    private ClientScopes() {
    }

    // ---- tolerant attribute reading ------------------------------------------------------

    /** A value trimmed, or "" when it is not a string (a list yields its first element). */
    static String clean(Object value) {
        if (value instanceof String s) {
            return s.strip();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "" : clean(list.get(0));
        }
        return "";
    }

    static String attribute(Map<String, ?> attributes, String key) {
        return attributes == null ? "" : clean(attributes.get(key));
    }

    static boolean truthy(Object value) {
        return TRUTHY.contains(clean(value).toLowerCase(Locale.ROOT));
    }

    /**
     * Parse a {@code ##}-separated attribute value into slugs, dropping blanks and duplicates
     * (order kept). Commas are accepted too: a human editing the attribute writes {@code a, b}.
     */
    static List<String> split(Object value) {
        String raw = clean(value);
        if (raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String chunk : raw.split(Pattern.quote(SEPARATOR), -1)) {
            for (String part : chunk.split(",", -1)) {
                String p = part.strip();
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
        }
        return List.copyOf(out);
    }

    // ---- public helpers ------------------------------------------------------------------

    /**
     * The institution a {@code <slug>-<app>} client-id names, or {@code null}.
     *
     * <p>Matched against known slugs (never by splitting on the first hyphen); the
     * <b>longest</b> matching slug wins. The bare slug (no hyphen) is not a client of that
     * institution.
     */
    public static String ownerFromClientId(String clientId, Collection<String> knownSlugs) {
        String cid = clean(clientId);
        if (cid.isEmpty() || knownSlugs == null) {
            return null;
        }
        String best = null;
        for (String slug : knownSlugs) {
            if (slug == null || slug.isEmpty()) {
                continue;
            }
            if (cid.startsWith(slug + "-") && (best == null || slug.length() > best.length())) {
                best = slug;
            }
        }
        return best;
    }

    /**
     * The client-id an institution's app must have (ADR-0001 §5.3): {@code <slug>-<app>}.
     *
     * <p>The delegated create path supplies the app part only; the prefix is the institution the
     * caller was resolved to. The app part is lowercased and must match the slug charset; an app
     * part that already starts with the slug (a pasted client-id) is refused.
     *
     * @throws ClientScopeException when the app part is unusable
     */
    public static String clientIdFor(String institution, String app) {
        String slug = clean(institution);
        String name = clean(app).toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(name).matches()) {
            throw new ClientScopeException(
                    "App name " + repr(app) + " is not usable in a client-id: use lowercase letters, digits "
                            + "and hyphen (the client becomes '" + slug + "-<app>').");
        }
        if (name.equals(slug) || name.startsWith(slug + "-")) {
            throw new ClientScopeException(
                    "App name " + repr(app) + " already starts with the institution slug " + repr(slug)
                            + " — give the app part only, e.g. 'portal' for '" + slug + "-portal'.");
        }
        return slug + "-" + name;
    }

    /**
     * Institutions that lose this client when {@code before} becomes {@code after}, sorted. Both
     * sides are expanded against the registry first, so narrowing "all" to a selection reports
     * every institution that drops out.
     */
    public static List<String> removedInstitutions(ClientScope before, ClientScope after,
                                                   Collection<String> knownSlugs) {
        TreeSet<String> lost = new TreeSet<>(before.covered(knownSlugs));
        lost.removeAll(after.covered(knownSlugs));
        return List.copyOf(lost);
    }

    /**
     * Scope attribute keys offered in a generic client-update body, sorted. Non-empty means the
     * update must be refused (400) — scope changes go through {@code PUT …/scope}.
     */
    public static List<String> scopeAttributesIn(Collection<String> attributeKeys) {
        TreeSet<String> offered = new TreeSet<>();
        if (attributeKeys != null) {
            for (String key : attributeKeys) {
                if (SCOPE_ATTRIBUTES.contains(key)) {
                    offered.add(key);
                }
            }
        }
        return List.copyOf(offered);
    }

    /**
     * The access-assignment guard: institutions (by group name) a scoped client is NOT available
     * to. Group names are matched case-insensitively against the registry (pre-ADR-0001 groups are
     * UPPERCASE); a group that is not an institution is left alone, and an unscoped
     * ({@code platform}) client refuses nobody. Result sorted and de-duplicated; non-empty ⇒ 400.
     */
    public static List<String> institutionsOutsideScope(ClientScope scope, Collection<String> groupNames,
                                                        Collection<String> knownSlugs) {
        if (!scope.isScoped() || groupNames == null) {
            return List.of();
        }
        java.util.Map<String, String> slugs = new java.util.HashMap<>();
        for (String s : knownSlugs) {
            if (s != null) {
                slugs.put(s.toLowerCase(Locale.ROOT), s);
            }
        }
        TreeSet<String> offending = new TreeSet<>();
        for (String name : groupNames) {
            String slug = slugs.get(clean(name).toLowerCase(Locale.ROOT));
            if (slug != null && !scope.covers(slug)) {
                offending.add(slug);
            }
        }
        return List.copyOf(offending);
    }

    /** The refusal message for {@link #institutionsOutsideScope} offenders. */
    public static String outsideScopeMessage(String clientId, ClientScope scope, List<String> offending) {
        String what = scope.crossCutting()
                ? "a cross-cutting client scoped to "
                + (scope.institutions().isEmpty() ? "no institution" : pyList(scope.institutions()))
                : "owned by institution '" + scope.owner() + "'";
        return "Client '" + clientId + "' is not available to institution(s) " + pyList(offending)
                + ": it is " + what + ". Change the client's scope first (PUT /client/clients/" + clientId
                + "/scope) — for an app several institutions share, mark it cross-cutting and name them.";
    }

    static void assertKnown(String slug, Collection<String> known, String field) {
        if (!SLUG.matcher(slug).matches()) {
            throw new ClientScopeException(field + ": " + repr(slug)
                    + " is not a valid institution slug (ADR-0001 §5.1: lowercase letters, digits and hyphen).");
        }
        if (known.isEmpty()) {
            throw new ClientScopeException("The institution registry is empty, so no institution can be "
                    + "validated. Check configs/institutions.yaml and INSTITUTION_REGISTRY_CONFIG.");
        }
        if (!known.contains(slug)) {
            throw new ClientScopeException(field + ": " + repr(slug) + " is not an institution in the registry. "
                    + "Known: " + pyList(new ArrayList<>(new TreeSet<>(known)))
                    + ". Onboard it first (configs/institutions.yaml).");
        }
    }

    static String repr(String value) {
        return value == null ? "None" : "'" + value + "'";
    }

    static String pyList(List<String> values) {
        List<String> quoted = new ArrayList<>();
        for (String v : values) {
            quoted.add("'" + v + "'");
        }
        return "[" + String.join(", ", quoted) + "]";
    }
}
