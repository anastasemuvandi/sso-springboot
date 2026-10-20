package rw.gov.risa.sso.wrapper.policy.fgap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.Guardrail;
import rw.gov.risa.sso.wrapper.policy.delegated.PermissionTemplate;
import rw.gov.risa.sso.wrapper.policy.delegated.PyRepr;
import rw.gov.risa.sso.wrapper.policy.delegated.ResolvedGrant;

/**
 * FGAP v2 projection — the delegated-permission templates expressed as Keycloak 26 admin
 * permission objects (ADR-0002). Pure: it takes a parsed registry plus the concrete
 * roles/clients/groups that exist and returns a desired state with stable names.
 *
 * <ul>
 *   <li><b>Patterns become enumerations</b>, filtered through the same
 *       {@link ResolvedGrant} predicates the wrapper enforces — one implementation of
 *       allow/deny.</li>
 *   <li><b>Denies become absences</b>; {@link #assertPlanSafe} re-checks the output.</li>
 *   <li><b>One permission per (resource type, scope, tier-set)</b>, because the
 *       {@code admin-permissions} resource server combines permissions UNANIMOUSLY: two
 *       permissions on the same object would veto each other.</li>
 * </ul>
 */
public final class FgapProjection {

    public static final String SCOPE_GROUP_VIEW = "view";
    public static final String SCOPE_GROUP_VIEW_MEMBERS = "view-members";
    public static final String SCOPE_GROUP_MANAGE_MEMBERS = "manage-members";
    public static final String SCOPE_GROUP_MANAGE_MEMBERSHIP = "manage-membership";
    public static final String SCOPE_ROLE_MAP = "map-role";
    public static final String SCOPE_CLIENT_VIEW = "view";
    public static final String SCOPE_CLIENT_MANAGE = "manage";
    public static final String SCOPE_USER_MAP_ROLES = "map-roles";

    /** A permission over every object of its type — spelled out so it is visible in the plan. */
    public static final String ALL_RESOURCES = "*";

    /** A caller who may list a group's members must also be able to see the group. */
    public static final boolean GRANT_GROUP_VIEW_WITH_MEMBERS = true;

    public static final String NAME_PREFIX = "inst";
    /** Institution key of the realm-wide plan. */
    public static final String SHARED = "_shared";

    /** One capability -> one (resource type, scope), in this (significant) order. */
    private record CapScope(Capability capability, ResourceType type, String scope) {
    }

    private static final List<CapScope> CAPABILITY_SCOPES = List.of(
            new CapScope(Capability.VIEW_MEMBERS, ResourceType.GROUPS, SCOPE_GROUP_VIEW_MEMBERS),
            new CapScope(Capability.MANAGE_MEMBERS, ResourceType.GROUPS, SCOPE_GROUP_MANAGE_MEMBERS),
            new CapScope(Capability.MANAGE_MEMBERSHIP, ResourceType.GROUPS, SCOPE_GROUP_MANAGE_MEMBERSHIP),
            new CapScope(Capability.MAP_ROLES, ResourceType.ROLES, SCOPE_ROLE_MAP),
            new CapScope(Capability.VIEW_CLIENTS, ResourceType.CLIENTS, SCOPE_CLIENT_VIEW),
            new CapScope(Capability.MANAGE_CLIENTS, ResourceType.CLIENTS, SCOPE_CLIENT_MANAGE));

    private FgapProjection() {
    }

    // ---- naming ----------------------------------------------------------- //

    private static String tierKey(Collection<String> tiers) {
        return String.join("+", new ArrayList<>(tiers).stream().sorted().toList());
    }

    /** {@code inst:<slug>:<tier>[+<tier>...]} — one policy per set of tiers. */
    public static String policyName(String slug, Collection<String> tiers) {
        return NAME_PREFIX + ":" + slug + ":" + tierKey(tiers);
    }

    /** {@code inst:<slug>:<tiers>:<resource type lowercased>:<scope>}. */
    public static String permissionName(String slug, Collection<String> tiers, ResourceType type, String scope) {
        return NAME_PREFIX + ":" + slug + ":" + tierKey(tiers) + ":" + type.wire().toLowerCase(java.util.Locale.ROOT)
                + ":" + scope;
    }

    /** True for an object this projection owns (so an audit can spot strays). */
    public static boolean isManagedName(String name) {
        return name != null && name.startsWith(NAME_PREFIX + ":");
    }

    /** (slug, tier-key) of a managed name: {@code inst:minagri:institution-admin[:groups]}. */
    public record ManagedName(String slug, String tiers) {
    }

    public static Optional<ManagedName> parseManagedName(String name) {
        if (!isManagedName(name)) {
            return Optional.empty();
        }
        String[] parts = name.split(":", -1);
        if (parts.length < 3) {
            return Optional.empty();
        }
        return Optional.of(new ManagedName(parts[1], parts[2]));
    }

    // ---- projection ------------------------------------------------------- //

    static List<String> groupResources(ResolvedGrant grant, Collection<String> subgroups, SubgroupMode mode) {
        if (mode == SubgroupMode.PROPAGATE || !grant.includeSubgroups()) {
            return List.of(grant.groupPath());
        }
        List<String> out = new ArrayList<>();
        out.add(grant.groupPath());
        subgroups.stream()
                .filter(p -> !p.equals(grant.groupPath()) && grant.coversGroupPath(p))
                .sorted()
                .forEach(out::add);
        return out;
    }

    /** The scopes a tier's capabilities imply for one resource type (sorted, distinct). */
    static List<String> scopesFor(ResolvedGrant grant, ResourceType type) {
        List<String> scopes = new ArrayList<>();
        for (CapScope cs : CAPABILITY_SCOPES) {
            if (cs.type() == type && grant.has(cs.capability())) {
                scopes.add(cs.scope());
            }
        }
        if (type == ResourceType.GROUPS && !scopes.isEmpty() && GRANT_GROUP_VIEW_WITH_MEMBERS
                && !scopes.contains(SCOPE_GROUP_VIEW)) {
            scopes.add(SCOPE_GROUP_VIEW);
        }
        return List.copyOf(new TreeSet<>(scopes));
    }

    static boolean grantsScope(ResolvedGrant grant, ResourceType type, String scope, String resource) {
        return switch (type) {
            case GROUPS -> scopesFor(grant, type).contains(scope) && grant.coversGroupPath(resource);
            case ROLES -> scopesFor(grant, type).contains(scope) && grant.mayMapRole(resource);
            case CLIENTS -> {
                if (scope.equals(SCOPE_CLIENT_MANAGE)) {
                    yield grant.mayManageClient(resource);
                }
                if (scope.equals(SCOPE_CLIENT_VIEW)) {
                    yield grant.mayViewClient(resource) || grant.mayManageClient(resource);
                }
                yield false;
            }
            default -> false;
        };
    }

    private record Triple(ResourceType type, String scope, String object) {
    }

    private record Bucket(ResourceType type, String scope, List<String> tiers) {
    }

    private static final Comparator<List<String>> TUPLE_ORDER = (a, b) -> {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    };

    private static final Comparator<Bucket> BUCKET_ORDER = Comparator
            .comparing((Bucket k) -> k.type().wire())
            .thenComparing(Bucket::scope)
            .thenComparing(Bucket::tiers, TUPLE_ORDER);

    /** {@link #renderInstitution(DelegatedPermissionRegistry, String, Collection, Collection, Collection, SubgroupMode)} with no enumeration inputs. */
    public static InstitutionPlan renderInstitution(DelegatedPermissionRegistry registry, String slug) {
        return renderInstitution(registry, slug, List.of(), List.of(), List.of(), SubgroupMode.ENUMERATE);
    }

    /**
     * Every tier {@code assignments} enables for {@code slug}, projected. A withheld tier
     * produces no objects. For every (resource type, scope, object) the SET of granting tiers
     * is computed and one permission is emitted per distinct set, so no two permissions cover
     * the same object and scope.
     *
     * @param roles     realm roles that exist (any institution; filtered by may-map-role)
     * @param clients   clientIds that exist (filtered by view/manage)
     * @param subgroups group paths that exist (filtered by covers-group-path)
     * @throws FgapProjectionException if the output would be unsafe
     */
    public static InstitutionPlan renderInstitution(DelegatedPermissionRegistry registry, String slug,
                                                    Collection<String> roles, Collection<String> clients,
                                                    Collection<String> subgroups, SubgroupMode subgroupMode) {
        List<PermissionTemplate> templates = registry.enabledTemplates(slug);
        Map<String, ResolvedGrant> grants = new LinkedHashMap<>();
        Map<String, String> grantRoles = new LinkedHashMap<>();
        for (PermissionTemplate t : templates) {
            grants.put(t.name(), t.render(slug));
            grantRoles.put(t.name(), t.grantRoleFor(slug));
        }

        TreeSet<String> groupCandidates = new TreeSet<>();
        for (ResolvedGrant g : grants.values()) {
            groupCandidates.addAll(groupResources(g, subgroups, subgroupMode));
        }
        Map<ResourceType, List<String>> candidates = new LinkedHashMap<>();
        candidates.put(ResourceType.GROUPS, List.copyOf(groupCandidates));
        candidates.put(ResourceType.ROLES, List.copyOf(new TreeSet<>(roles)));
        candidates.put(ResourceType.CLIENTS, List.copyOf(new TreeSet<>(clients)));

        Map<Triple, List<String>> granted = new LinkedHashMap<>();
        for (Map.Entry<String, ResolvedGrant> e : grants.entrySet()) {
            for (Map.Entry<ResourceType, List<String>> c : candidates.entrySet()) {
                for (String scope : scopesFor(e.getValue(), c.getKey())) {
                    for (String obj : c.getValue()) {
                        if (grantsScope(e.getValue(), c.getKey(), scope, obj)) {
                            granted.computeIfAbsent(new Triple(c.getKey(), scope, obj), k -> new ArrayList<>())
                                    .add(e.getKey());
                        }
                    }
                }
            }
        }

        Map<Bucket, List<String>> buckets = new TreeMap<>(BUCKET_ORDER);
        for (Map.Entry<Triple, List<String>> e : granted.entrySet()) {
            List<String> tiers = e.getValue().stream().sorted().toList();
            buckets.computeIfAbsent(new Bucket(e.getKey().type(), e.getKey().scope(), tiers), k -> new ArrayList<>())
                    .add(e.getKey().object());
        }

        Map<String, RolePolicy> policies = new TreeMap<>();
        List<ScopePermission> permissions = new ArrayList<>();
        for (Map.Entry<Bucket, List<String>> e : buckets.entrySet()) {
            Bucket k = e.getKey();
            List<String> objects = e.getValue();
            String name = policyName(slug, k.tiers());
            List<String> tierRoles = k.tiers().stream().map(grantRoles::get).toList();
            policies.computeIfAbsent(name, n -> new RolePolicy(n,
                    "Holders of " + PyRepr.list(tierRoles) + " in '" + slug + "' (tiers: " + PyRepr.list(k.tiers())
                            + "). Projected from configs/delegated-permissions/templates.json; do not edit by hand.",
                    tierRoles));
            permissions.add(new ScopePermission(
                    permissionName(slug, k.tiers(), k.type(), k.scope()),
                    "'" + slug + "': " + k.scope() + " on " + objects.size() + " " + k.type().wire()
                            + " for tiers " + PyRepr.list(k.tiers()) + ".",
                    k.type(),
                    objects.stream().sorted().toList(),
                    List.of(k.scope()),
                    List.of(name)));
        }

        InstitutionPlan plan = new InstitutionPlan(slug, List.copyOf(policies.values()), permissions);
        assertPlanSafe(plan, registry.guardrail());
        return plan;
    }

    /**
     * The one realm-wide object: the role-mapping ACTION gate ({@code Users/map-roles} over
     * all users), whose single role policy lists every institution's map-roles grant roles.
     * Per-institution copies would overlap on every user and veto each other, so onboarding
     * an institution updates this shared object. Empty when no institution has a map-roles tier.
     */
    public static Optional<InstitutionPlan> renderShared(DelegatedPermissionRegistry registry,
                                                         Collection<String> slugs) {
        TreeSet<String> roles = new TreeSet<>();
        for (String slug : new TreeSet<>(slugs)) {
            for (PermissionTemplate t : registry.enabledTemplates(slug)) {
                if (t.capabilities().contains(Capability.MAP_ROLES)) {
                    roles.add(t.grantRoleFor(slug));
                }
            }
        }
        if (roles.isEmpty()) {
            return Optional.empty();
        }
        RolePolicy policy = new RolePolicy(NAME_PREFIX + ":" + SHARED + ":map-roles",
                "Any delegated admin permitted to map roles, in any institution. "
                        + "Shared because a per-institution copy would cover every user and, "
                        + "under the resource server's UNANIMOUS strategy, veto every other "
                        + "institution's copy. Which users and which roles remain bounded by "
                        + "the per-institution Groups and Roles permissions.",
                List.copyOf(roles));
        ScopePermission permission = new ScopePermission(
                NAME_PREFIX + ":" + SHARED + ":users:" + SCOPE_USER_MAP_ROLES,
                "Permits the role-mapping action; target and role are bounded elsewhere.",
                ResourceType.USERS,
                List.of(ALL_RESOURCES),
                List.of(SCOPE_USER_MAP_ROLES),
                List.of(policy.name()));
        InstitutionPlan plan = new InstitutionPlan(SHARED, List.of(policy), List.of(permission));
        assertPlanSafe(plan, registry.guardrail());
        return Optional.of(plan);
    }

    /** Every institution (sorted, distinct) then the shared plan if any. */
    public static List<InstitutionPlan> renderAll(DelegatedPermissionRegistry registry, Collection<String> slugs,
                                                  Collection<String> roles, Collection<String> clients,
                                                  Collection<String> subgroups, SubgroupMode subgroupMode) {
        List<InstitutionPlan> plans = new ArrayList<>();
        for (String slug : new TreeSet<>(slugs)) {
            plans.add(renderInstitution(registry, slug, roles, clients, subgroups, subgroupMode));
        }
        renderShared(registry, slugs).ifPresent(plans::add);
        return List.copyOf(plans);
    }

    // ---- safety ----------------------------------------------------------- //

    /**
     * Refuse a plan that reaches outside the institution or at a broad role: roles must be
     * {@code inst:<slug>:*} and not forbidden, clients {@code <slug>-*}, groups {@code /<slug>}
     * or below, a Users permission may only carry {@code map-roles}; no permission may be
     * resource-less or policy-less.
     */
    public static void assertPlanSafe(InstitutionPlan plan, Guardrail guardrail) {
        String slug = plan.institution();
        String roleNs = "inst:" + slug + ":";
        String clientNs = slug + "-";
        String groupRoot = "/" + slug;

        for (ScopePermission p : plan.permissions()) {
            String where = "permission " + PyRepr.str(p.name());
            switch (p.resourceType()) {
                case ROLES -> {
                    for (String role : p.resources()) {
                        if (!role.startsWith(roleNs)) {
                            throw new FgapProjectionException(where + " would grant map-role on " + PyRepr.str(role)
                                    + ", outside " + PyRepr.str(roleNs + "*") + " — map-roles scoping depends on it");
                        }
                        if (guardrail.isForbiddenRealmRole(role)) {
                            throw new FgapProjectionException(where + " would grant map-role on forbidden broad role "
                                    + PyRepr.str(role));
                        }
                    }
                }
                case CLIENTS -> {
                    for (String clientId : p.resources()) {
                        if (!clientId.startsWith(clientNs)) {
                            throw new FgapProjectionException(where + " would reach client " + PyRepr.str(clientId)
                                    + ", outside " + PyRepr.str(clientNs + "*"));
                        }
                    }
                }
                case GROUPS -> {
                    for (String path : p.resources()) {
                        if (!path.equals(groupRoot) && !path.startsWith(groupRoot + "/")) {
                            throw new FgapProjectionException(where + " would reach group " + PyRepr.str(path)
                                    + ", outside " + PyRepr.str(groupRoot));
                        }
                    }
                }
                case USERS -> {
                    if (!p.scopes().equals(List.of(SCOPE_USER_MAP_ROLES))) {
                        throw new FgapProjectionException(where + " is a Users permission with scopes "
                                + PyRepr.list(p.scopes()) + "; only " + PyRepr.str(SCOPE_USER_MAP_ROLES)
                                + " is allowed");
                    }
                }
                default -> {
                }
            }
            if (p.resources().isEmpty()) {
                throw new FgapProjectionException(where + " has no resources — it would be a no-op");
            }
            if (p.policies().isEmpty()) {
                throw new FgapProjectionException(where + " has no policy — an unpolicied permission grants everyone");
            }
        }
    }
}
