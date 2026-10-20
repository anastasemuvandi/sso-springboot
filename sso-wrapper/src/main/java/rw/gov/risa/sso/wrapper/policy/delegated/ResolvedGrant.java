package rw.gov.risa.sso.wrapper.policy.delegated;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a caller may do inside ONE institution — all {@code {slug}} placeholders resolved.
 *
 * <p>The merge of every template the caller holds for that institution: allows add up,
 * denies are absolute (a deny in any template wins, so combining tiers can never widen
 * access beyond the strictest one's exclusions).
 */
public record ResolvedGrant(
        String institution,
        List<String> templates,
        Set<Capability> capabilities,
        String groupPath,
        boolean includeSubgroups,
        List<String> roleAllow,
        List<String> roleDeny,
        List<String> clientAllow,
        List<String> clientDeny) {

    public ResolvedGrant {
        templates = List.copyOf(templates);
        capabilities = capabilities.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(Capability.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
        roleAllow = List.copyOf(roleAllow);
        roleDeny = List.copyOf(roleDeny);
        clientAllow = List.copyOf(clientAllow);
        clientDeny = List.copyOf(clientDeny);
    }

    // ---- capability ------------------------------------------------------- //

    public boolean has(Capability capability) {
        return capabilities.contains(capability);
    }

    /** Capability wire names, sorted (what {@code InstitutionContext.capabilities} exposes). */
    public List<String> capabilityNames() {
        return capabilities.stream().map(Capability::wire).sorted().toList();
    }

    // ---- map-roles scoping ------------------------------------------------- //

    /**
     * True only for roles inside this institution's namespace and allowed: requires
     * {@code map-roles}, then no deny matches, then an allow matches. This is what makes
     * it structurally impossible for a local admin to hand out a platform role.
     */
    public boolean mayMapRole(String roleName) {
        if (!has(Capability.MAP_ROLES)) {
            return false;
        }
        if (Glob.matchesAny(roleName, roleDeny)) {
            return false;
        }
        return Glob.matchesAny(roleName, roleAllow);
    }

    // ---- client-management scoping ----------------------------------------- //

    public boolean mayViewClient(String clientId) {
        return mayViewClient(clientId, false);
    }

    /**
     * @param shared the caller asserts "this client is cross-cutting and its institution
     *               scope includes mine" (decided by the client-scope module). It stands in
     *               for the allow-pattern only — the capability is still required and
     *               denies still win.
     */
    public boolean mayViewClient(String clientId, boolean shared) {
        return mayClient(clientId, Capability.VIEW_CLIENTS, shared);
    }

    public boolean mayManageClient(String clientId) {
        return mayManageClient(clientId, false);
    }

    /** Which clients this admin may change access on ({@code <slug>-*} by default). */
    public boolean mayManageClient(String clientId, boolean shared) {
        return mayClient(clientId, Capability.MANAGE_CLIENTS, shared);
    }

    private boolean mayClient(String clientId, Capability capability, boolean shared) {
        if (!has(capability)) {
            return false;
        }
        if (Glob.matchesAny(clientId, clientDeny)) {
            return false;
        }
        return shared || Glob.matchesAny(clientId, clientAllow);
    }

    // ---- member scoping ----------------------------------------------------- //

    /** True if a group path is the institution's group (or a department of it, when subgroups are included). */
    public boolean coversGroupPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (path.equals(groupPath)) {
            return true;
        }
        return includeSubgroups && path.startsWith(groupPath + "/");
    }

    // ---- reporting ---------------------------------------------------------- //

    /** JSON-safe view (insertion-ordered maps) — powers /institution-admin/permissions. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("institution", institution);
        out.put("templates", templates);
        out.put("capabilities", capabilityNames());
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("group", groupPath);
        members.put("include_subgroups", includeSubgroups);
        out.put("members", members);
        out.put("roles", allowDeny(roleAllow, roleDeny));
        out.put("clients", allowDeny(clientAllow, clientDeny));
        return out;
    }

    static Map<String, Object> allowDeny(List<String> allow, List<String> deny) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allow", allow);
        m.put("deny", deny);
        return m;
    }

    // ---- merging ------------------------------------------------------------ //

    /**
     * Combine several templates held for the same institution into one grant
     * ({@code merge_grants}). Capabilities union, patterns concatenated with
     * order-preserving dedupe, {@code includeSubgroups} if any, group path from the first.
     *
     * @throws IllegalArgumentException for an empty list or grants of different institutions
     */
    public static ResolvedGrant merge(List<ResolvedGrant> grants) {
        if (grants == null || grants.isEmpty()) {
            throw new IllegalArgumentException("merge_grants() needs at least one grant");
        }
        Set<String> institutions = new LinkedHashSet<>();
        grants.forEach(g -> institutions.add(g.institution()));
        if (institutions.size() != 1) {
            throw new IllegalArgumentException("cannot merge grants for different institutions");
        }
        ResolvedGrant first = grants.get(0);
        if (grants.size() == 1) {
            return first;
        }
        Set<String> templates = new LinkedHashSet<>();
        Set<Capability> caps = EnumSet.noneOf(Capability.class);
        Set<String> ra = new LinkedHashSet<>();
        Set<String> rd = new LinkedHashSet<>();
        Set<String> ca = new LinkedHashSet<>();
        Set<String> cd = new LinkedHashSet<>();
        boolean subgroups = false;
        for (ResolvedGrant g : grants) {
            templates.addAll(g.templates());
            caps.addAll(g.capabilities());
            subgroups |= g.includeSubgroups();
            ra.addAll(g.roleAllow());
            rd.addAll(g.roleDeny());
            ca.addAll(g.clientAllow());
            cd.addAll(g.clientDeny());
        }
        return new ResolvedGrant(first.institution(), List.copyOf(templates), caps, first.groupPath(), subgroups,
                List.copyOf(ra), List.copyOf(rd), List.copyOf(ca), List.copyOf(cd));
    }
}
