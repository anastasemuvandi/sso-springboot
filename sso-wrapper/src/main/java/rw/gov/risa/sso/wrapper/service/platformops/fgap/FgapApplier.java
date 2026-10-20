package rw.gov.risa.sso.wrapper.service.platformops.fgap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.fgap.FgapProjection;
import rw.gov.risa.sso.wrapper.policy.fgap.InstitutionPlan;
import rw.gov.risa.sso.wrapper.policy.fgap.ResourceType;
import rw.gov.risa.sso.wrapper.policy.fgap.RolePolicy;
import rw.gov.risa.sso.wrapper.policy.fgap.ScopePermission;
import rw.gov.risa.sso.wrapper.policy.fgap.SubgroupMode;

/**
 * Applies an FGAP v2 plan to a realm through the Admin REST API — the no-Terraform twin of
 * {@code infra/terraform} for tests and offline deployments (port of {@code kc26_spike.py apply}).
 * Terraform stays the production path.
 *
 * <ol>
 *   <li>turn on {@code adminPermissionsEnabled} (which creates the {@code admin-permissions} resource
 *       server; needs the {@code admin-fine-grained-authz:v2} feature);</li>
 *   <li>one ROLE policy per tier-set ({@code required=false}: any one of the roles), converged when the
 *       role list drifted;</li>
 *   <li>one SCOPE permission per (resource type, scope, tier-set), {@code decisionStrategy=UNANIMOUS}.
 *       Resources are Keycloak OBJECT ids (group / role / client ids) with the resource type; an EMPTY
 *       list means every object of the type ({@code "*"} in the plan). An existing permission is
 *       CONVERGED: its resource list is compared and the difference PUT (idempotent is not convergent —
 *       a client onboarded after the first apply must still enter the permission).</li>
 * </ol>
 *
 * Unresolvable names (a role, group or client the realm lacks) are reported, never guessed; a permission
 * with no resolvable resource is SKIPPED rather than written as "every object of this type".
 */
public final class FgapApplier {

    public static final String ADMIN_PERMISSIONS_CLIENT = "admin-permissions";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TYPE_NAMES = Set.of("Groups", "Users", "Clients", "Roles");

    private final AdminRest kc;
    private final boolean dryRun;
    private final List<String> lines = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private int created;
    private int updated;

    public FgapApplier(AdminRest kc, boolean dryRun) {
        this.kc = kc;
        this.dryRun = dryRun;
    }

    /** What happened. {@code problems} lists everything skipped or unresolved. */
    public record Report(boolean dryRun, int created, int updated, List<String> lines, List<String> problems) {
        public boolean clean() {
            return problems.isEmpty();
        }
    }

    /** The realm's inventory the projection enumerates: realm roles, clientIds, every group path. */
    public record Inventory(List<String> roles, List<String> clients, List<String> groupPaths) {
    }

    // ---- inputs ----------------------------------------------------------------------------

    /** Roles, clients and group paths (children read from {@code /children}: {@code subGroups} is empty on 26). */
    public static Inventory inventory(AdminRest kc) {
        List<String> roles = new ArrayList<>();
        kc.requireGet("roles?first=0&max=100000").forEach(r -> roles.add(r.path("name").asText()));
        List<String> clients = new ArrayList<>();
        kc.requireGet("clients?first=0&max=100000").forEach(c -> clients.add(c.path("clientId").asText()));
        List<String> paths = new ArrayList<>();
        for (JsonNode top : kc.requireGet("groups?first=0&max=100000&briefRepresentation=true")) {
            walk(kc, top, paths);
        }
        return new Inventory(roles, clients, paths);
    }

    private static void walk(AdminRest kc, JsonNode group, List<String> paths) {
        String path = group.path("path").asText(null);
        if (path != null) {
            paths.add(path);
        }
        JsonNode children = kc.requireGet("groups/" + group.path("id").asText() + "/children?first=0&max=100000");
        for (JsonNode child : children) {
            walk(kc, child, paths);
        }
    }

    /**
     * The plan for {@code slugs} projected against what the realm actually holds (the reference's
     * {@code _plans(kc)}): only roles, clients and group paths that exist are enumerated.
     */
    public static List<InstitutionPlan> livePlans(DelegatedPermissionRegistry registry, Inventory inventory,
                                                  List<String> slugs, SubgroupMode mode) {
        return FgapProjection.renderAll(registry, slugs, inventory.roles(), inventory.clients(),
                inventory.groupPaths(), mode);
    }

    /** {@code configs/generated/fgap-v2-plan.json} → plans, grouped by institution in file order. */
    public static List<InstitutionPlan> fromPlanFile(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("not a JSON plan: " + e.getMessage(), e);
        }
        Map<String, List<RolePolicy>> policies = new LinkedHashMap<>();
        Map<String, List<ScopePermission>> permissions = new LinkedHashMap<>();
        for (JsonNode p : root.path("policies")) {
            policies.computeIfAbsent(p.path("institution").asText(), k -> new ArrayList<>())
                    .add(new RolePolicy(p.path("name").asText(), p.path("description").asText(""), strings(p.path("roles"))));
            permissions.computeIfAbsent(p.path("institution").asText(), k -> new ArrayList<>());
        }
        for (JsonNode p : root.path("permissions")) {
            String type = p.path("resource_type").asText();
            ResourceType rt = java.util.Arrays.stream(ResourceType.values()).filter(t -> t.wire().equals(type))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown resource_type " + type));
            permissions.computeIfAbsent(p.path("institution").asText(), k -> new ArrayList<>())
                    .add(new ScopePermission(p.path("name").asText(), p.path("description").asText(""), rt,
                            strings(p.path("resources")), strings(p.path("scopes")), strings(p.path("policies"))));
            policies.computeIfAbsent(p.path("institution").asText(), k -> new ArrayList<>());
        }
        List<InstitutionPlan> plans = new ArrayList<>();
        for (String inst : policies.keySet()) {
            plans.add(new InstitutionPlan(inst, policies.get(inst), permissions.getOrDefault(inst, List.of())));
        }
        return plans;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    // ---- apply -----------------------------------------------------------------------------

    public Report apply(List<InstitutionPlan> plans) {
        enableAdminPermissions();
        JsonNode rs = kc.findClient(ADMIN_PERMISSIONS_CLIENT);
        if (rs == null) {
            if (dryRun) {
                problems.add("no '" + ADMIN_PERMISSIONS_CLIENT + "' client yet (admin permissions are off); "
                        + "a real run enables them first");
                return report();
            }
            throw new IllegalStateException("No '" + ADMIN_PERMISSIONS_CLIENT + "' client in realm '" + kc.realm()
                    + "' after enabling admin permissions — is the admin-fine-grained-authz:v2 feature on?");
        }
        String rsPath = "clients/" + rs.path("id").asText() + "/authz/resource-server/";
        Map<String, JsonNode> existingPolicies = byName(kc.requireGet(rsPath + "policy?first=0&max=100000"));
        Map<String, JsonNode> existingPermissions = byName(kc.requireGet(rsPath + "permission?first=0&max=100000"));
        Map<String, String> roleIds = new HashMap<>();

        Set<String> unusablePolicies = new HashSet<>();
        for (InstitutionPlan plan : plans) {
            lines.add(plan.institution());
            for (RolePolicy policy : plan.policies()) {
                applyPolicy(rsPath, policy, existingPolicies, roleIds, unusablePolicies);
            }
        }
        Map<String, String> policyIds = new HashMap<>();
        byName(kc.requireGet(rsPath + "policy?first=0&max=100000")).forEach((n, p) -> policyIds.put(n, p.path("id").asText()));
        Map<String, String> scopeIds = new HashMap<>();
        kc.requireGet(rsPath + "scope?first=0&max=100000").forEach(s -> scopeIds.put(s.path("name").asText(),
                s.path("id").asText()));
        for (InstitutionPlan plan : plans) {
            for (ScopePermission permission : plan.permissions()) {
                applyPermission(rsPath, permission, existingPermissions, policyIds, scopeIds, unusablePolicies);
            }
        }
        lines.add(dryRun ? "DRY RUN — nothing written." : "Done. " + created + " object(s) created, " + updated
                + " updated.");
        return report();
    }

    private Report report() {
        return new Report(dryRun, created, updated, List.copyOf(lines), List.copyOf(problems));
    }

    void enableAdminPermissions() {
        JsonNode realm = kc.requireGet("");
        if (realm.path("adminPermissionsEnabled").asBoolean(false)) {
            lines.add("  admin permissions already enabled on the realm");
            return;
        }
        if (dryRun) {
            lines.add("  [would ] enable admin permissions on the realm");
            return;
        }
        ObjectNode body = (ObjectNode) realm;
        body.put("adminPermissionsEnabled", true);
        kc.require("PUT", "", body);
        if (!kc.requireGet("").path("adminPermissionsEnabled").asBoolean(false)) {
            throw new IllegalStateException("Setting adminPermissionsEnabled had no effect. KC_FEATURES must include "
                    + "admin-fine-grained-authz:v2.");
        }
        lines.add("  admin permissions enabled on the realm (creates the admin-permissions client)");
    }

    private void applyPolicy(String rsPath, RolePolicy policy, Map<String, JsonNode> existing,
                             Map<String, String> roleIds, Set<String> unusable) {
        List<String> ids = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String role : policy.roles()) {
            String id = roleIds.computeIfAbsent(role, r -> {
                JsonNode rep = kc.findRealmRole(r);
                return rep == null ? "" : rep.path("id").asText();
            });
            if (id.isEmpty()) {
                missing.add(role);
            } else {
                ids.add(id);
            }
        }
        if (!missing.isEmpty()) {
            problems.add("policy " + policy.name() + ": realm role(s) " + missing
                    + " do not exist (run delegated-apply first)");
        }
        if (ids.isEmpty()) {
            unusable.add(policy.name());
            lines.add("  [SKIP  ] policy      " + policy.name() + "  (no role resolved)");
            return;
        }
        JsonNode current = existing.get(policy.name());
        if (current != null) {
            Set<String> have = currentRoleIds(current);
            if (have.equals(new HashSet<>(ids))) {
                lines.add("  [exists] policy      " + policy.name());
                return;
            }
            if (dryRun) {
                lines.add("  [would ] policy      " + policy.name() + "  roles -> " + policy.roles());
                return;
            }
            String pid = current.path("id").asText();
            kc.require("PUT", rsPath + "policy/role/" + pid, rolePolicyBody(policy, ids, pid));
            updated++;
            lines.add("  [updated] policy     " + policy.name());
            return;
        }
        if (dryRun) {
            lines.add("  [would ] policy      " + policy.name() + "  roles=" + policy.roles());
            return;
        }
        kc.require("POST", rsPath + "policy/role", rolePolicyBody(policy, ids, null));
        created++;
        lines.add("  [created] policy     " + policy.name());
    }

    private static Map<String, Object> rolePolicyBody(RolePolicy policy, List<String> roleIds, String id) {
        List<Map<String, Object>> roles = new ArrayList<>();
        // required=false: "any one of these roles" — the OR that makes a shared-tier policy work.
        roleIds.forEach(r -> roles.add(AdminRest.map("id", r, "required", false)));
        Map<String, Object> body = AdminRest.map("name", policy.name(), "description", shorten(policy.description()),
                "logic", "POSITIVE", "decisionStrategy", "UNANIMOUS", "roles", roles);
        if (id != null) {
            body.put("id", id);
            body.put("type", "role");
        }
        return body;
    }

    private static Set<String> currentRoleIds(JsonNode policy) {
        Set<String> out = new TreeSet<>();
        JsonNode raw = policy.path("config").path("roles");
        try {
            JsonNode entries = raw.isTextual() ? JSON.readTree(raw.asText()) : raw;
            entries.forEach(e -> out.add(e.isObject() ? e.path("id").asText() : e.asText()));
        } catch (IOException e) {
            // unreadable: treated as drift, converged by a PUT
        }
        return out;
    }

    private void applyPermission(String rsPath, ScopePermission permission, Map<String, JsonNode> existing,
                                 Map<String, String> policyIds, Map<String, String> scopeIds, Set<String> unusable) {
        List<String> missingScopes = permission.scopes().stream().filter(s -> !scopeIds.containsKey(s)).toList();
        if (!missingScopes.isEmpty()) {
            problems.add("permission " + permission.name() + ": unknown scope(s) " + missingScopes + " on the "
                    + permission.resourceType().wire() + " resource type");
            lines.add("  [SKIP  ] permission  " + permission.name() + "  (unknown scopes " + missingScopes + ")");
            return;
        }
        List<String> policies = new ArrayList<>();
        for (String p : permission.policies()) {
            String id = policyIds.get(p);
            if (id == null || unusable.contains(p)) {
                problems.add("permission " + permission.name() + ": policy " + p + " is missing");
                lines.add("  [SKIP  ] permission  " + permission.name() + "  (policy " + p + " missing)");
                return;
            }
            policies.add(id);
        }
        List<String> want = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        boolean all = permission.resources().contains(FgapProjection.ALL_RESOURCES);
        for (String human : permission.resources()) {
            if (FgapProjection.ALL_RESOURCES.equals(human)) {
                continue;
            }
            String id = objectId(permission.resourceType(), human);
            if (id == null) {
                unresolved.add(human);
            } else {
                want.add(id);
            }
        }
        if (!unresolved.isEmpty()) {
            problems.add("permission " + permission.name() + ": could not resolve " + unresolved);
        }
        if (want.isEmpty() && !all) {
            lines.add("  [SKIP  ] permission  " + permission.name() + "  (no resources resolved; not widening it to "
                    + "every " + permission.resourceType().wire() + ")");
            return;
        }
        Map<String, Object> body = AdminRest.map("name", permission.name(),
                "description", shorten(permission.description()),
                "resourceType", permission.resourceType().wire(),
                "resources", want,
                "scopes", permission.scopes().stream().map(scopeIds::get).toList(),
                "policies", policies,
                "decisionStrategy", "UNANIMOUS");
        JsonNode current = existing.get(permission.name());
        if (current != null) {
            String pid = current.path("id").asText();
            Set<String> have = new TreeSet<>();
            boolean haveAll = false;
            for (JsonNode r : kc.requireGet(rsPath + "policy/" + pid + "/resources")) {
                String display = r.path("displayName").asText(r.path("name").asText());
                if (TYPE_NAMES.contains(display)) {
                    haveAll = true;
                } else if (r.hasNonNull("name")) {
                    have.add(r.path("name").asText());
                }
            }
            if (have.equals(new TreeSet<>(want)) && haveAll == all) {
                lines.add("  [exists] permission  " + permission.name());
                return;
            }
            if (dryRun) {
                lines.add("  [would ] permission  " + permission.name() + "  resources -> " + permission.resources());
                return;
            }
            body.put("id", pid);
            kc.require("PUT", rsPath + "permission/scope/" + pid, body);
            updated++;
            lines.add("  [updated] permission " + permission.name());
            return;
        }
        if (dryRun) {
            lines.add("  [would ] permission  " + permission.name() + "  " + permission.resourceType().wire() + " "
                    + permission.scopes() + " over " + permission.resources().size() + " resource(s)");
            return;
        }
        kc.require("POST", rsPath + "permission/scope", body);
        created++;
        lines.add("  [created] permission " + permission.name() + "  " + permission.resourceType().wire() + " "
                + permission.scopes());
    }

    /** Keycloak object id for a plan resource (group path / role name / clientId), or null. */
    String objectId(ResourceType type, String human) {
        JsonNode rep = switch (type) {
            case GROUPS -> kc.findGroup(human);
            case ROLES -> kc.findRealmRole(human);
            case CLIENTS -> kc.findClient(human);
            case USERS -> kc.findUser(human);
        };
        return rep == null ? null : rep.path("id").asText(null);
    }

    private static Map<String, JsonNode> byName(JsonNode list) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (list != null && list.isArray()) {
            list.forEach(n -> {
                if (n.hasNonNull("name")) {
                    out.put(n.path("name").asText(), n);
                }
            });
        }
        return out;
    }

    /** RESOURCE_SERVER_POLICY.DESCRIPTION is varchar(255): collapse whitespace and trim to 250. */
    static String shorten(String text) {
        String t = text == null ? "" : String.join(" ", text.strip().split("\\s+"));
        return t.length() <= 250 ? t : t.substring(0, 249).stripTrailing() + "…";
    }
}
