package rw.gov.risa.sso.wrapper.policy.delegated;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The pure half of {@code delegated-apply}: which realm roles each institution's enabled
 * tiers require, and whether each already exists. Idempotent and additive — the plan never
 * contains a deletion (removing a role would revoke live access as a side effect of a
 * config edit).
 *
 * @param items deduplicated by role (several tiers legitimately provision the same short role); first wins
 */
public record RoleProvisioningPlan(List<String> institutions, List<Item> items) {

    public enum Action {
        CREATE("create"),
        EXISTS("exists");

        private final String wire;

        Action(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public record Item(String institution, String template, String role, Action action) {
    }

    public RoleProvisioningPlan {
        institutions = List.copyOf(institutions);
        items = List.copyOf(items);
    }

    /**
     * @param slugs         institutions to provision, in request order
     * @param existingRoles realm role names that already exist
     * @throws DelegatedPermissionConfigException for an invalid slug or an institution with every tier withheld
     */
    public static RoleProvisioningPlan plan(DelegatedPermissionRegistry registry, List<String> slugs,
                                            Collection<String> existingRoles) {
        Set<String> existing = new HashSet<>(existingRoles);
        List<Item> planned = new ArrayList<>();
        for (String slug : slugs) {
            registry.grantFor(slug); // raises for an invalid slug / all tiers withheld
            for (PermissionTemplate t : registry.enabledTemplates(slug)) {
                for (String role : t.rolesToProvision(slug)) {
                    planned.add(new Item(slug, t.name(), role, existing.contains(role) ? Action.EXISTS : Action.CREATE));
                }
            }
        }
        Set<String> seen = new HashSet<>();
        List<Item> unique = new ArrayList<>();
        for (Item i : planned) {
            if (seen.add(i.role())) {
                unique.add(i);
            }
        }
        return new RoleProvisioningPlan(slugs, unique);
    }

    /** Roles to create, in plan order. */
    public List<String> toCreate() {
        return items.stream().filter(i -> i.action() == Action.CREATE).map(Item::role).toList();
    }

    /** Roles that already exist, in plan order ({@code unchanged}). */
    public List<String> unchanged() {
        return items.stream().filter(i -> i.action() == Action.EXISTS).map(Item::role).toList();
    }

    /** The description the reference puts on a created role. */
    public static String roleDescription(Item item, String configSource) {
        return "Delegated permissions: " + item.template() + " for " + item.institution()
                + " (managed by " + configSource + ")";
    }
}
