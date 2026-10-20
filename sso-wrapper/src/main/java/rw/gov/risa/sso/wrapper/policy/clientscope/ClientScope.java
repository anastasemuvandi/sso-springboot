package rw.gov.risa.sso.wrapper.policy.clientscope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which institutions may see, be assigned, and use one client (port of {@code ClientScope}).
 *
 * <p>Read with {@link #parse} (tolerant — never throws), written with {@link #requested} (strict —
 * throws {@link ClientScopeException}). {@link #covers} is the only question enforcement asks.
 *
 * @param clientId        the client's {@code clientId}
 * @param owner           owning slug for an institution client, else {@code null}
 * @param crossCutting    platform-wide client
 * @param allInstitutions cross-cutting with {@code "*"} (dynamic: evaluated against the live registry)
 * @param institutions    cross-cutting explicit selection (order kept, de-duplicated)
 * @param inferred        {@code owner} came from the client-id prefix rather than an attribute
 */
public record ClientScope(String clientId, String owner, boolean crossCutting, boolean allInstitutions,
                          List<String> institutions, boolean inferred) {

    public ClientScope {
        institutions = institutions == null ? List.of() : List.copyOf(new LinkedHashSet<>(institutions));
    }

    // ---- kind ----------------------------------------------------------------------------

    /** {@code cross-cutting} | {@code institution} | {@code platform}. */
    public ClientScopeKind kind() {
        if (crossCutting) {
            return ClientScopeKind.CROSS_CUTTING;
        }
        if (owner != null && !owner.isEmpty()) {
            return ClientScopeKind.INSTITUTION;
        }
        return ClientScopeKind.PLATFORM;
    }

    /** False for a {@code platform} client: nothing recorded, nothing enforced. */
    public boolean isScoped() {
        return kind() != ClientScopeKind.PLATFORM;
    }

    /**
     * Cross-cutting with neither {@code *} nor any institution: nobody can use it. The reference
     * logs a warning when it parses one; callers may do the same.
     */
    public boolean coversNobody() {
        return crossCutting && !allInstitutions && institutions.isEmpty();
    }

    // ---- enforcement ---------------------------------------------------------------------

    /**
     * May institution {@code slug} see this client and assign it to its staff? A platform client
     * is covered by nobody; a cross-cutting one by its selection (or everyone for {@code *}); an
     * institution client only by its owner.
     */
    public boolean covers(String slug) {
        String target = ClientScopes.clean(slug);
        if (target.isEmpty()) {
            return false;
        }
        if (crossCutting) {
            return allInstitutions || institutions.contains(target);
        }
        return target.equals(owner);
    }

    /**
     * Every institution currently covered, expanded and sorted — for diffing a change. {@code *}
     * expands against {@code knownSlugs} here only; stored it stays {@code "*"}.
     */
    public List<String> covered(Collection<String> knownSlugs) {
        if (crossCutting) {
            TreeSet<String> out = new TreeSet<>();
            if (allInstitutions) {
                if (knownSlugs != null) {
                    for (String s : knownSlugs) {
                        if (s != null && !s.isEmpty()) {
                            out.add(s);
                        }
                    }
                }
            } else {
                out.addAll(institutions);
            }
            return List.copyOf(out);
        }
        return owner != null && !owner.isEmpty() ? List.of(owner) : List.of();
    }

    // ---- storage -------------------------------------------------------------------------

    /**
     * The client attributes that record this scope. Every owned key is always present, empty when
     * unset, so a read-modify-write can clear a scope.
     */
    public Map<String, String> toAttributes() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(ClientScopes.ATTR_OWNER, crossCutting ? "" : (owner == null ? "" : owner));
        out.put(ClientScopes.ATTR_CROSS_CUTTING, crossCutting ? "true" : "");
        String institutionsValue;
        if (crossCutting && allInstitutions) {
            institutionsValue = ClientScopes.ALL_INSTITUTIONS;
        } else if (crossCutting) {
            institutionsValue = String.join(ClientScopes.SEPARATOR, institutions);
        } else {
            institutionsValue = "";
        }
        out.put(ClientScopes.ATTR_INSTITUTIONS, institutionsValue);
        return out;
    }

    /**
     * JSON-safe view (snake_case keys): {@code client_id, kind, owner_institution, cross_cutting,
     * all_institutions, institutions, inferred}.
     */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client_id", clientId);
        out.put("kind", kind().code());
        out.put("owner_institution", owner);
        out.put("cross_cutting", crossCutting);
        out.put("all_institutions", allInstitutions);
        out.put("institutions", new ArrayList<>(institutions));
        out.put("inferred", inferred);
        return out;
    }

    // ---- construction --------------------------------------------------------------------

    public static ClientScope platform(String clientId) {
        return new ClientScope(clientId, null, false, false, List.of(), false);
    }

    public static ClientScope forInstitution(String clientId, String slug) {
        return new ClientScope(clientId, slug, false, false, List.of(), false);
    }

    public static ClientScope forAllInstitutions(String clientId) {
        return new ClientScope(clientId, null, true, true, List.of(), false);
    }

    public static ClientScope forSelected(String clientId, Collection<String> slugs) {
        return new ClientScope(clientId, null, true, false,
                slugs == null ? List.of() : new ArrayList<>(slugs), false);
    }

    /**
     * The scope a Keycloak client records. Tolerant: never throws.
     *
     * <ul>
     *   <li>cross-cutting with {@code *} → all institutions (dynamic);</li>
     *   <li>cross-cutting with an empty list covers <b>nobody</b> (never "everyone");</li>
     *   <li>an owner attribute naming an unknown slug is kept as written;</li>
     *   <li>no attribute → owner inferred from the longest known {@code <slug>-} prefix
     *       ({@code inferred=true}), else platform.</li>
     * </ul>
     *
     * @param clientId   the client's {@code clientId} (callers fall back to the UUID when absent)
     * @param attributes the client's attributes (may be {@code null}); values may be strings or lists
     * @param knownSlugs registry slugs, for prefix inference
     */
    public static ClientScope parse(String clientId, Map<String, ?> attributes, Collection<String> knownSlugs) {
        String cid = ClientScopes.clean(clientId);
        Map<String, ?> attrs = attributes == null ? Map.of() : attributes;

        if (ClientScopes.truthy(attrs.get(ClientScopes.ATTR_CROSS_CUTTING))) {
            String raw = ClientScopes.attribute(attrs, ClientScopes.ATTR_INSTITUTIONS);
            if (raw.equals(ClientScopes.ALL_INSTITUTIONS)) {
                return forAllInstitutions(cid);
            }
            return forSelected(cid, ClientScopes.split(raw));
        }

        String owner = ClientScopes.attribute(attrs, ClientScopes.ATTR_OWNER);
        if (!owner.isEmpty()) {
            return forInstitution(cid, owner);
        }

        String inferredOwner = ClientScopes.ownerFromClientId(cid, knownSlugs);
        if (inferredOwner != null) {
            return new ClientScope(cid, inferredOwner, false, false, List.of(), true);
        }
        return platform(cid);
    }

    /** {@link #parse(String, Map, Collection)} with no registry (no prefix inference). */
    public static ClientScope parse(String clientId, Map<String, ?> attributes) {
        return parse(clientId, attributes, List.of());
    }

    /**
     * Validate a scope a platform admin asked for (create or {@code PUT …/scope}).
     *
     * <ul>
     *   <li>cross-cutting <b>and</b> an owner → refused ("not both");</li>
     *   <li>not cross-cutting with institutions → refused;</li>
     *   <li>not cross-cutting, no owner → platform; with owner → it must be a known valid slug;</li>
     *   <li>{@code *} mixed with named institutions → refused; {@code *} alone → all;</li>
     *   <li>cross-cutting with no selection → refused ("cannot be saved with no institution");</li>
     *   <li>every selected slug must be valid and in the registry; an empty registry refuses.</li>
     * </ul>
     *
     * @throws ClientScopeException on any of the above
     */
    public static ClientScope requested(String clientId, boolean crossCutting, Collection<String> institutions,
                                        String ownerInstitution, Collection<String> knownSlugs) {
        Set<String> known = new HashSet<>();
        if (knownSlugs != null) {
            for (String s : knownSlugs) {
                if (s != null && !s.isEmpty()) {
                    known.add(s);
                }
            }
        }
        LinkedHashSet<String> selectedSet = new LinkedHashSet<>();
        if (institutions != null) {
            for (String s : institutions) {
                String c = ClientScopes.clean(s);
                if (!c.isEmpty()) {
                    selectedSet.add(c);
                }
            }
        }
        List<String> selected = List.copyOf(selectedSet);
        String owner = ClientScopes.clean(ownerInstitution);
        boolean wantsAll = selected.contains(ClientScopes.ALL_INSTITUTIONS);

        if (crossCutting && !owner.isEmpty()) {
            throw new ClientScopeException("A client is either owned by one institution or cross-cutting, "
                    + "not both — got owner_institution=" + ClientScopes.repr(owner) + " with "
                    + "cross_cutting=true. Drop the owner to make it cross-cutting.");
        }

        if (!crossCutting) {
            if (!selected.isEmpty()) {
                throw new ClientScopeException("institutions may only be set on a cross-cutting client. Set "
                        + "cross_cutting=true, or use owner_institution for a client that belongs to one "
                        + "institution.");
            }
            if (owner.isEmpty()) {
                return platform(clientId);
            }
            ClientScopes.assertKnown(owner, known, "owner_institution");
            return forInstitution(clientId, owner);
        }

        if (wantsAll) {
            List<String> others = new ArrayList<>();
            for (String s : selected) {
                if (!s.equals(ClientScopes.ALL_INSTITUTIONS)) {
                    others.add(s);
                }
            }
            if (!others.isEmpty()) {
                throw new ClientScopeException("institutions cannot mix '*' with named institutions "
                        + ClientScopes.pyList(others)
                        + ". '*' already includes every institution, now and in future.");
            }
            return forAllInstitutions(clientId);
        }

        if (selected.isEmpty()) {
            throw new ClientScopeException("A cross-cutting client must say which institutions may use it: "
                    + "either institutions=['*'] for every institution (including ones onboarded later), or "
                    + "an explicit list of slugs. It cannot be saved with no institution assigned.");
        }
        for (String slug : selected) {
            ClientScopes.assertKnown(slug, known, "institutions");
        }
        return forSelected(clientId, selected);
    }
}
