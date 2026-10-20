package rw.gov.risa.sso.wrapper.policy.delegated;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The pure half of {@code delegated-check} / {@code GET /delegated-permissions/audit}: judge a
 * realm snapshot against the templates. The service layer implements {@link RealmView} over
 * the Keycloak Admin API (effective = composite-expanded roles; client roles only for the
 * clients the guardrail names; a failed member listing returns an empty list) and adds
 * {@code realm} / {@code guardrail_enforced} to the response.
 *
 * <p>Findings, in the order they matter: guardrail violations (a live privilege escalation),
 * unmanaged grants (a grant role for a tier withheld from that institution — usually made
 * by hand in the console), missing roles (fixed by apply). {@link Report#clean()} is what a
 * pipeline gates on.
 */
public final class DelegatedAudit {

    private DelegatedAudit() {
    }

    /** What the audit needs from the realm. */
    public interface RealmView {
        /** Every realm role name. */
        List<String> realmRoles();

        /** Users holding the realm role (directly). Empty on a lookup failure. */
        List<Holder> membersOf(String realmRole);

        /** A user's effective realm roles and their roles on the guardrail's forbidden clients. */
        HolderRoles rolesOf(String userId);
    }

    public record Holder(String userId, String username) {
    }

    public record HolderRoles(List<String> realmRoles, Map<String, List<String>> clientRoles) {
    }

    public record TierHolder(String userId, String username, boolean guardrailOk) {
    }

    public record Tier(String template, String grantRole, boolean grantRoleExists, List<String> capabilities,
                       List<TierHolder> holders) {
    }

    public record InstitutionAudit(String institution, boolean configured, List<Tier> tiers) {
    }

    public record Violation(String institution, String userId, String username, List<String> grantRoles,
                            List<String> templates, List<GuardrailViolation> forbidden, boolean isPlatformAdmin) {
    }

    public record UnmanagedGrant(String institution, String template, String role, String reason) {
    }

    public record MissingRole(String institution, String template, String role) {
    }

    public record Report(String source, List<InstitutionAudit> institutions, List<Violation> guardrailViolations,
                         List<UnmanagedGrant> unmanagedGrants, List<MissingRole> missingRoles) {
        public boolean clean() {
            return guardrailViolations.isEmpty() && unmanagedGrants.isEmpty() && missingRoles.isEmpty();
        }
    }

    /**
     * @param institutions  explicit slugs to audit (exactly those, even with nothing delegated yet);
     *                      null/empty = every slug with a grant role in the realm plus every configured one
     * @param platformRoles realm roles that make an account a platform admin (super_admin/admin/system_admin)
     */
    public static Report audit(DelegatedPermissionRegistry registry, RealmView realm,
                               Collection<String> institutions, Set<String> platformRoles) {
        List<String> allRoles = realm.realmRoles().stream().filter(r -> r != null && !r.isEmpty()).toList();
        Set<String> existing = new java.util.HashSet<>(allRoles);

        Map<String, List<String>> discovered = registry.grantRolesIn(allRoles);
        List<String> slugs;
        if (institutions != null && !institutions.isEmpty()) {
            slugs = new ArrayList<>(new TreeSet<>(institutions));
        } else {
            Set<String> s = new TreeSet<>(discovered.keySet());
            s.addAll(registry.configuredInstitutions());
            slugs = new ArrayList<>(s);
        }

        List<InstitutionAudit> out = new ArrayList<>();
        List<UnmanagedGrant> unmanaged = new ArrayList<>();
        List<MissingRole> missing = new ArrayList<>();
        Map<String, Checked> checkedUsers = new HashMap<>();
        Map<List<String>, ViolationBuilder> violations = new TreeMap<>((a, b) -> {
            int c = a.get(0).compareTo(b.get(0));
            return c != 0 ? c : a.get(1).compareTo(b.get(1));
        });

        for (String slug : slugs) {
            List<PermissionTemplate> enabled = registry.enabledTemplates(slug);
            Set<String> enabledNames = new LinkedHashSet<>();
            enabled.forEach(t -> enabledNames.add(t.name()));

            for (String templateName : discovered.getOrDefault(slug, List.of())) {
                if (!enabledNames.contains(templateName)) {
                    unmanaged.add(new UnmanagedGrant(slug, templateName,
                            registry.templates().get(templateName).grantRoleFor(slug),
                            "template " + PyRepr.str(templateName) + " is not enabled for " + PyRepr.str(slug)
                                    + " but its grant role exists in the realm"));
                }
            }

            List<Tier> tiers = new ArrayList<>();
            for (PermissionTemplate template : enabled) {
                for (String role : template.rolesToProvision(slug)) {
                    if (!existing.contains(role)) {
                        missing.add(new MissingRole(slug, template.name(), role));
                    }
                }
                String grantRole = template.grantRoleFor(slug);
                List<TierHolder> holders = new ArrayList<>();
                if (existing.contains(grantRole)) {
                    for (Holder member : realm.membersOf(grantRole)) {
                        String userId = member.userId();
                        String username = member.username() != null && !member.username().isEmpty()
                                ? member.username() : userId;
                        Checked checked = checkedUsers.computeIfAbsent(userId, uid -> {
                            HolderRoles roles = realm.rolesOf(uid);
                            boolean platform = roles.realmRoles().stream().anyMatch(platformRoles::contains);
                            return new Checked(platform,
                                    registry.guardrail().violations(roles.realmRoles(), roles.clientRoles()));
                        });
                        holders.add(new TierHolder(userId, username, checked.forbidden.isEmpty()));
                        if (!checked.forbidden.isEmpty()) {
                            ViolationBuilder v = violations.computeIfAbsent(List.of(slug, userId),
                                    k -> new ViolationBuilder(slug, userId, username, checked));
                            v.grantRoles.add(grantRole);
                            v.templates.add(template.name());
                        }
                    }
                }
                tiers.add(new Tier(template.name(), grantRole, existing.contains(grantRole),
                        template.capabilities().stream().map(Capability::wire).sorted().toList(),
                        List.copyOf(holders)));
            }
            out.add(new InstitutionAudit(slug, registry.configuredInstitutions().contains(slug), List.copyOf(tiers)));
        }

        List<Violation> found = new ArrayList<>();
        for (ViolationBuilder v : violations.values()) {
            found.add(new Violation(v.institution, v.userId, v.username, List.copyOf(v.grantRoles),
                    List.copyOf(v.templates), v.checked.forbidden, v.checked.platform));
        }
        return new Report(registry.source(), List.copyOf(out), List.copyOf(found), List.copyOf(unmanaged),
                List.copyOf(missing));
    }

    private record Checked(boolean platform, List<GuardrailViolation> forbidden) {
    }

    private static final class ViolationBuilder {
        final String institution;
        final String userId;
        final String username;
        final Checked checked;
        final List<String> grantRoles = new ArrayList<>();
        final List<String> templates = new ArrayList<>();

        ViolationBuilder(String institution, String userId, String username, Checked checked) {
            this.institution = institution;
            this.userId = userId;
            this.username = username;
            this.checked = checked;
        }
    }

    /** Convenience {@link RealmView} over plain maps (tests, offline tooling). */
    public static RealmView staticView(List<String> realmRoles, Map<String, List<Holder>> members,
                                       Map<String, HolderRoles> rolesByUser) {
        Map<String, List<Holder>> m = new LinkedHashMap<>(members);
        return new RealmView() {
            @Override
            public List<String> realmRoles() {
                return realmRoles;
            }

            @Override
            public List<Holder> membersOf(String realmRole) {
                return m.getOrDefault(realmRole, List.of());
            }

            @Override
            public HolderRoles rolesOf(String userId) {
                return rolesByUser.getOrDefault(userId, new HolderRoles(List.of(), Map.of()));
            }
        };
    }
}
