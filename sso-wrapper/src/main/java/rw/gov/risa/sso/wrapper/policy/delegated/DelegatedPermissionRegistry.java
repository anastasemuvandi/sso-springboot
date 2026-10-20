package rw.gov.risa.sso.wrapper.policy.delegated;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The parsed, validated {@code templates.json}. Build it with
 * {@link DelegatedPermissionsParser#fromMap(Object, String)} or
 * {@link DelegatedPermissionsLoader}.
 *
 * @param version          always 1
 * @param source           where it was loaded from, for diagnostics
 * @param templates        name -> template, in file order
 * @param defaultTemplates {@code assignments.default}
 * @param byInstitution    {@code assignments.by_institution}: slug -> template names, in file order
 */
public record DelegatedPermissionRegistry(
        int version,
        String source,
        Guardrail guardrail,
        Map<String, PermissionTemplate> templates,
        List<String> defaultTemplates,
        Map<String, List<String>> byInstitution) {

    private static final System.Logger LOG = System.getLogger(DelegatedPermissionRegistry.class.getName());

    public DelegatedPermissionRegistry {
        templates = Collections.unmodifiableMap(new LinkedHashMap<>(templates));
        defaultTemplates = List.copyOf(defaultTemplates);
        Map<String, List<String>> by = new LinkedHashMap<>();
        byInstitution.forEach((k, v) -> by.put(k, List.copyOf(v)));
        byInstitution = Collections.unmodifiableMap(by);
    }

    // ---- assignment -------------------------------------------------------- //

    /** Templates in force for one institution (its override, else the default), in assignment order. */
    public List<PermissionTemplate> enabledTemplates(String slug) {
        List<String> names = byInstitution.getOrDefault(slug, defaultTemplates);
        List<PermissionTemplate> out = new ArrayList<>();
        for (String n : names) {
            PermissionTemplate t = templates.get(n);
            if (t != null) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /** Institutions explicitly listed in {@code assignments.by_institution}, sorted. */
    public List<String> configuredInstitutions() {
        return byInstitution.keySet().stream().sorted().toList();
    }

    /**
     * {slug: template names whose grant role appears in {@code roleNames}}, sorted by slug.
     *
     * <p>Discovery, not authorisation: unlike {@link #resolveCaller} this does NOT drop
     * templates disabled for the institution — a grant role for a withheld tier is
     * exactly the drift an audit must report.
     */
    public Map<String, List<String>> grantRolesIn(Collection<String> roleNames) {
        Map<String, List<String>> found = new TreeMap<>();
        if (roleNames != null) {
            for (String role : roleNames) {
                for (PermissionTemplate t : templates.values()) {
                    t.slugForRole(role).ifPresent(slug ->
                            found.computeIfAbsent(slug, k -> new ArrayList<>()).add(t.name()));
                }
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        found.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(out);
    }

    // ---- resolution -------------------------------------------------------- //

    /**
     * Map a caller's realm roles to {slug: merged grant}, slugs in first-seen order.
     *
     * <p>Authority comes from the roles themselves — holding {@code inst:minagri:admin}
     * IS the grant. A tier withheld from an institution does not become active just
     * because someone holds its role (logged and ignored).
     */
    public Map<String, ResolvedGrant> resolveCaller(Collection<String> realmRoles) {
        Map<String, List<ResolvedGrant>> perSlug = new LinkedHashMap<>();
        if (realmRoles != null) {
            for (String role : realmRoles) {
                for (PermissionTemplate t : templates.values()) {
                    Optional<String> slug = t.slugForRole(role);
                    if (slug.isEmpty()) {
                        continue;
                    }
                    String s = slug.get();
                    boolean enabled = enabledTemplates(s).stream().anyMatch(e -> e.name().equals(t.name()));
                    if (!enabled) {
                        LOG.log(Level.WARNING, "delegated-permissions: role " + PyRepr.str(role)
                                + " maps to template " + PyRepr.str(t.name()) + ", which is not enabled for "
                                + PyRepr.str(s) + " — ignoring");
                        continue;
                    }
                    perSlug.computeIfAbsent(s, k -> new ArrayList<>()).add(t.render(s));
                }
            }
        }
        Map<String, ResolvedGrant> out = new LinkedHashMap<>();
        perSlug.forEach((slug, grants) -> out.put(slug, ResolvedGrant.merge(grants)));
        return Collections.unmodifiableMap(out);
    }

    /**
     * The full grant an institution's enabled templates define (its ceiling) — for platform
     * admins acting for an institution, and for previews.
     *
     * @throws DelegatedPermissionConfigException for an invalid slug, or when every tier is withheld
     */
    public ResolvedGrant grantFor(String slug) {
        if (!DelegatedNamespaces.isValidSlug(slug)) {
            throw new DelegatedPermissionConfigException(PyRepr.str(slug)
                    + " is not a valid institution slug (ADR-0001 §5.1: [a-z0-9-]+)");
        }
        List<PermissionTemplate> enabled = enabledTemplates(slug);
        if (enabled.isEmpty()) {
            throw new DelegatedPermissionConfigException(
                    "no delegated-permission templates are enabled for institution " + PyRepr.str(slug));
        }
        return ResolvedGrant.merge(enabled.stream().map(t -> t.render(slug)).toList());
    }

    /** JSON-safe dump of the whole config — the platform-admin read model ({@code GET /delegated-permissions}). */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", version);
        out.put("source", source);
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("forbidden_realm_roles", guardrail.forbiddenRealmRoles().stream().sorted().toList());
        g.put("forbidden_client_roles", new LinkedHashMap<>(guardrail.forbiddenClientRoles()));
        out.put("guardrail", g);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("default", defaultTemplates);
        a.put("by_institution", new LinkedHashMap<>(byInstitution));
        out.put("assignments", a);
        List<Object> ts = new ArrayList<>();
        for (PermissionTemplate t : templates.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("display_name", t.displayName());
            m.put("description", t.description());
            m.put("grant_role", t.grantRole());
            m.put("capabilities", t.capabilities().stream().map(Capability::wire).sorted().toList());
            Map<String, Object> members = new LinkedHashMap<>();
            members.put("group", t.group());
            members.put("include_subgroups", t.includeSubgroups());
            m.put("members", members);
            m.put("roles", ResolvedGrant.allowDeny(t.roleAllow(), t.roleDeny()));
            m.put("clients", ResolvedGrant.allowDeny(t.clientAllow(), t.clientDeny()));
            m.put("provision", Map.of("roles", t.provisionRoles()));
            ts.add(m);
        }
        out.put("templates", ts);
        return out;
    }
}
