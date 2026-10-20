package rw.gov.risa.sso.wrapper.policy.delegated;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns the generic JSON tree of {@code templates.json} (maps, lists, strings, numbers,
 * booleans — e.g. what Jackson's {@code readValue(..., Map.class)} returns) into a
 * validated {@link DelegatedPermissionRegistry}. Every check of the reference
 * {@code DelegatedPermissionRegistry.from_dict} / {@code _parse_template} /
 * {@code _validate_template} is here, in the same order, with the same messages.
 *
 * <p>Anything unsafe throws {@link DelegatedPermissionConfigException}; nothing is
 * defaulted silently.
 */
public final class DelegatedPermissionsParser {

    private static final System.Logger LOG = System.getLogger(DelegatedPermissionsParser.class.getName());

    private DelegatedPermissionsParser() {
    }

    /** {@code DelegatedPermissionRegistry.from_dict(raw, source)}. */
    public static DelegatedPermissionRegistry fromMap(Object raw, String source) {
        if (!(raw instanceof Map<?, ?> root)) {
            throw new DelegatedPermissionConfigException("template config must be a JSON object");
        }
        Object version = root.get("version");
        if (!isOne(version)) {
            throw new DelegatedPermissionConfigException(
                    "unsupported template config version " + pyRepr(version) + " (expected 1)");
        }

        Guardrail guardrail = parseGuardrail(orEmptyMap(root.get("guardrail")));

        Object rawTemplates = root.get("templates");
        if (!(rawTemplates instanceof Map<?, ?> tmap) || tmap.isEmpty()) {
            throw new DelegatedPermissionConfigException("templates must be a non-empty object");
        }
        Map<String, PermissionTemplate> templates = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : tmap.entrySet()) {
            String name = String.valueOf(e.getKey());
            templates.put(name, parseTemplate(name, e.getValue(), guardrail));
        }

        Object assignmentsRaw = orEmptyMap(root.get("assignments"));
        if (!(assignmentsRaw instanceof Map<?, ?> assignments)) {
            throw new DelegatedPermissionConfigException("assignments must be an object");
        }
        List<String> defaults = strList(assignments.get("default"), "assignments.default");
        if (defaults.isEmpty()) {
            throw new DelegatedPermissionConfigException("assignments.default must name at least one template");
        }

        Object byInstRaw = orEmptyMap(assignments.get("by_institution"));
        if (!(byInstRaw instanceof Map<?, ?> byInst)) {
            throw new DelegatedPermissionConfigException(
                    "assignments.by_institution must be an object of slug -> [template]");
        }
        Map<String, List<String>> byInstitution = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : byInst.entrySet()) {
            String slug = String.valueOf(e.getKey());
            if (!DelegatedNamespaces.isValidSlug(slug)) {
                throw new DelegatedPermissionConfigException(
                        "assignments.by_institution key " + PyRepr.str(slug) + " is not a valid slug");
            }
            byInstitution.put(slug, strList(e.getValue(), "assignments.by_institution." + slug));
        }

        Map<String, List<String>> scopes = new LinkedHashMap<>();
        scopes.put("default", defaults);
        scopes.putAll(byInstitution);
        for (Map.Entry<String, List<String>> e : scopes.entrySet()) {
            List<String> unknown = e.getValue().stream().filter(n -> !templates.containsKey(n)).toList();
            if (!unknown.isEmpty()) {
                throw new DelegatedPermissionConfigException(
                        "assignments." + e.getKey() + " references unknown template(s) " + PyRepr.list(unknown));
            }
        }

        return new DelegatedPermissionRegistry(1, source, guardrail, templates, defaults, byInstitution);
    }

    // ---- guardrail -------------------------------------------------------- //

    /** {@code Guardrail.from_dict}. Refuses a guardrail that forbids nothing. */
    public static Guardrail parseGuardrail(Object raw) {
        if (!(raw instanceof Map<?, ?> g)) {
            throw new DelegatedPermissionConfigException("guardrail must be an object");
        }
        Object realm = orEmptyList(g.get("forbidden_realm_roles"));
        Object clients = orEmptyMap(g.get("forbidden_client_roles"));
        if (!(realm instanceof List<?> realmList) || !realmList.stream().allMatch(String.class::isInstance)) {
            throw new DelegatedPermissionConfigException("guardrail.forbidden_realm_roles must be a list of role names");
        }
        if (!(clients instanceof Map<?, ?> clientMap)) {
            throw new DelegatedPermissionConfigException(
                    "guardrail.forbidden_client_roles must be an object of clientId -> [role patterns]");
        }
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : clientMap.entrySet()) {
            String clientId = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof List<?> patterns) || !patterns.stream().allMatch(String.class::isInstance)) {
                throw new DelegatedPermissionConfigException("guardrail.forbidden_client_roles["
                        + PyRepr.str(clientId) + "] must be a list of role patterns");
            }
            parsed.put(clientId, patterns.stream().map(String.class::cast).toList());
        }
        if (realmList.isEmpty() && parsed.isEmpty()) {
            throw new DelegatedPermissionConfigException(
                    "guardrail defines no forbidden roles — refusing to run with the broad-admin rule disabled");
        }
        Set<String> realmRoles = new TreeSet<>();
        realmList.forEach(r -> realmRoles.add((String) r));
        return new Guardrail(realmRoles, parsed);
    }

    // ---- templates -------------------------------------------------------- //

    static PermissionTemplate parseTemplate(String name, Object raw, Guardrail guardrail) {
        if (!(raw instanceof Map<?, ?> t)) {
            throw new DelegatedPermissionConfigException("templates." + name + " must be an object");
        }
        Object grantRole = t.get("grant_role");
        if (!(grantRole instanceof String gr) || gr.isEmpty()) {
            throw new DelegatedPermissionConfigException("templates." + name + ".grant_role is required");
        }

        Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        for (String c : strList(t.get("capabilities"), "templates." + name + ".capabilities")) {
            Capability cap = Capability.fromWire(c).orElseThrow(() -> new DelegatedPermissionConfigException(
                    "templates." + name + ".capabilities: " + PyRepr.str(c) + " is not a valid Capability. "
                            + "Known capabilities: " + Capability.knownList()));
            capabilities.add(cap);
        }

        Object members = orEmptyMap(t.get("members"));
        Object roles = orEmptyMap(t.get("roles"));
        Object clients = orEmptyMap(t.get("clients"));
        Object provision = orEmptyMap(t.get("provision"));
        Object[][] sections = {{"members", members}, {"roles", roles}, {"clients", clients}, {"provision", provision}};
        for (Object[] s : sections) {
            if (!(s[1] instanceof Map)) {
                throw new DelegatedPermissionConfigException("templates." + name + "." + s[0] + " must be an object");
            }
        }
        Map<?, ?> m = (Map<?, ?>) members;
        Map<?, ?> r = (Map<?, ?>) roles;
        Map<?, ?> c = (Map<?, ?>) clients;
        Map<?, ?> p = (Map<?, ?>) provision;

        Object displayName = t.get("display_name");
        Object group = m.get("group");
        PermissionTemplate template = new PermissionTemplate(
                name,
                truthy(displayName) ? pyStr(displayName) : name,
                asText(t.get("description")),
                gr,
                capabilities,
                truthy(group) ? pyStr(group) : DelegatedNamespaces.GROUP_ROOT,
                m.containsKey("include_subgroups") ? truthy(m.get("include_subgroups")) : true,
                strList(r.get("allow"), "templates." + name + ".roles.allow"),
                strList(r.get("deny"), "templates." + name + ".roles.deny"),
                strList(c.get("allow"), "templates." + name + ".clients.allow"),
                strList(c.get("deny"), "templates." + name + ".clients.deny"),
                strList(p.get("roles"), "templates." + name + ".provision.roles"));
        validateTemplate(template, guardrail);
        return template;
    }

    /**
     * Refuse a template that could grant outside its institution's namespace — the
     * structural rules that let the enforcement layer trust the config file.
     */
    public static void validateTemplate(PermissionTemplate template, Guardrail guardrail) {
        String where = "templates." + template.name();
        String ph = DelegatedNamespaces.SLUG_PLACEHOLDER;
        String roleNs = DelegatedNamespaces.ROLE_NAMESPACE;
        String clientNs = DelegatedNamespaces.CLIENT_NAMESPACE;
        String groupRoot = DelegatedNamespaces.GROUP_ROOT;

        if (!template.grantRole().contains(ph)) {
            throw new DelegatedPermissionConfigException(where + ".grant_role must contain " + PyRepr.str(ph)
                    + " — a grant role that is not per-institution would apply everywhere");
        }
        if (!template.grantRole().startsWith(roleNs)) {
            throw new DelegatedPermissionConfigException(where + ".grant_role must start with " + PyRepr.str(roleNs)
                    + " (got " + PyRepr.str(template.grantRole()) + ")");
        }
        if (guardrail.isForbiddenRealmRole(template.grantRole())) {
            throw new DelegatedPermissionConfigException(where + ".grant_role " + PyRepr.str(template.grantRole())
                    + " is a forbidden broad role");
        }
        if (!(template.group().equals(groupRoot) || template.group().startsWith(groupRoot + "/"))) {
            throw new DelegatedPermissionConfigException(where + ".members.group must be " + PyRepr.str(groupRoot)
                    + " or a subgroup of it (got " + PyRepr.str(template.group()) + ")");
        }
        for (String pattern : template.roleAllow()) {
            if (!pattern.startsWith(roleNs) || pattern.equals(roleNs)) {
                throw new DelegatedPermissionConfigException(where + ".roles.allow[" + PyRepr.str(pattern)
                        + "] must stay inside the institution namespace " + PyRepr.str(roleNs + "*")
                        + " — map-roles scoping depends on it");
            }
        }
        for (String pattern : template.clientAllow()) {
            if (!pattern.startsWith(clientNs) || pattern.equals(clientNs)) {
                throw new DelegatedPermissionConfigException(where + ".clients.allow[" + PyRepr.str(pattern)
                        + "] must stay inside the institution client namespace " + PyRepr.str(clientNs + "*"));
            }
        }
        // Belt-and-braces: prove no allow-pattern can reach a forbidden role.
        ResolvedGrant probe = template.render("probe");
        List<String> reachable = new ArrayList<>();
        for (String role : guardrail.forbiddenRealmRoles()) {
            if (Glob.matchesAny(role, probe.roleAllow())) {
                reachable.add(role);
            }
        }
        if (!reachable.isEmpty()) {
            throw new DelegatedPermissionConfigException(where + ".roles.allow can match forbidden broad role(s) "
                    + PyRepr.list(reachable.stream().sorted().toList()));
        }
        if (template.capabilities().contains(Capability.MAP_ROLES) && template.roleAllow().isEmpty()) {
            LOG.log(Level.WARNING, "delegated-permissions: " + where
                    + " has the map-roles capability but an empty roles.allow — it can assign nothing");
        }
    }

    // ---- Python value semantics ------------------------------------------- //

    private static List<String> strList(Object raw, String where) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            throw new DelegatedPermissionConfigException(where + " must be a list of strings");
        }
        return list.stream().map(String.class::cast).toList();
    }

    /** Descriptions are written as string lists in the JSON for readability. */
    private static String asText(Object value) {
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            list.forEach(v -> parts.add(pyStr(v)));
            return String.join("\n", parts);
        }
        return truthy(value) ? pyStr(value) : "";
    }

    /** {@code x or {}} */
    private static Object orEmptyMap(Object v) {
        return truthy(v) ? v : Map.of();
    }

    /** {@code x or []} */
    private static Object orEmptyList(Object v) {
        return truthy(v) ? v : List.of();
    }

    /** Python truthiness for JSON values. */
    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (v instanceof List<?> l) {
            return !l.isEmpty();
        }
        return true;
    }

    /** Python {@code str()} for JSON scalars. */
    static String pyStr(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        return String.valueOf(v);
    }

    private static String pyRepr(Object v) {
        return v instanceof String s ? PyRepr.str(s) : pyStr(v);
    }

    /** Python {@code version != 1} is false for 1, 1.0 and True. */
    private static boolean isOne(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return v instanceof Number n && n.doubleValue() == 1.0;
    }
}
