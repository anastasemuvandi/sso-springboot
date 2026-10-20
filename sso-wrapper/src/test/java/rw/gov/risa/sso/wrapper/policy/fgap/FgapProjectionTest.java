package rw.gov.risa.sso.wrapper.policy.fgap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.delegated.TestRepo;
import rw.gov.risa.sso.wrapper.policy.fgap.FgapDrift.ActualPermission;
import rw.gov.risa.sso.wrapper.policy.fgap.FgapDrift.ActualPolicy;
import rw.gov.risa.sso.wrapper.policy.fgap.FgapDrift.Finding;

/** Port of tests/e2e/test_fgap_v2_projection.py plus extra cases. */
class FgapProjectionTest {

    private static final String SLUG = "minagri";
    private static final List<String> ROLES = List.of("inst:minagri:admin", "inst:minagri:hr", "inst:minagri:staff",
            "inst:minagri:auditor", "inst:minisante:staff", "super_admin", "manage-users");
    private static final List<String> CLIENTS = List.of("minagri-kubaka-web", "minisante-hmis", "sso-client");
    private static final List<String> SUBGROUPS = List.of("/minagri", "/minagri/planning", "/minisante");

    private final DelegatedPermissionRegistry registry =
            DelegatedPermissionsLoader.loadFrom(TestRepo.path("configs/delegated-permissions/templates.json"));
    private final InstitutionPlan plan = FgapProjection.renderInstitution(registry, SLUG, ROLES, CLIENTS, SUBGROUPS,
            SubgroupMode.ENUMERATE);

    private record Triple(ResourceType type, String scope, String resource) {
    }

    private static List<Triple> triples(InstitutionPlan plan) {
        return plan.permissions().stream()
                .flatMap(p -> p.scopes().stream().flatMap(s -> p.resources().stream()
                        .map(r -> new Triple(p.resourceType(), s, r))))
                .toList();
    }

    private static Set<String> resourcesOf(InstitutionPlan plan, ResourceType type) {
        return plan.permissions().stream().filter(p -> p.resourceType() == type)
                .flatMap(p -> p.resources().stream()).collect(Collectors.toSet());
    }

    @Test
    void noTwoPermissionsCoverTheSameObjectAndScope() {
        List<Triple> t = triples(plan);
        assertThat(new HashSet<>(t)).hasSameSizeAs(t);
    }

    @Test
    void deniedRoleIsAbsentRatherThanDenied() {
        Set<String> mapped = resourcesOf(plan, ResourceType.ROLES);
        assertThat(mapped).doesNotContain("inst:minagri:admin")
                .contains("inst:minagri:hr", "inst:minagri:staff", "inst:minagri:auditor");
    }

    @Test
    void nothingReachesOutsideTheInstitution() {
        for (ScopePermission p : plan.permissions()) {
            for (String r : p.resources()) {
                assertThat(r).doesNotContain("minisante");
                switch (p.resourceType()) {
                    case ROLES -> assertThat(r).startsWith("inst:minagri:");
                    case CLIENTS -> assertThat(r).startsWith("minagri-");
                    case GROUPS -> assertThat(r.equals("/minagri") || r.startsWith("/minagri/")).isTrue();
                    default -> {
                    }
                }
            }
        }
    }

    @Test
    void broadRolesAreNeverMappable() {
        Set<String> all = plan.permissions().stream().flatMap(p -> p.resources().stream()).collect(Collectors.toSet());
        assertThat(all).doesNotContain("super_admin", "manage-users");
    }

    @Test
    void tiersThatShareAGrantShareOnePermission() {
        List<ScopePermission> staff = plan.permissions().stream()
                .filter(p -> p.resourceType() == ResourceType.ROLES && p.resources().contains("inst:minagri:staff"))
                .toList();
        assertThat(staff).hasSize(1);
        String policyName = staff.get(0).policies().get(0);
        RolePolicy policy = plan.policies().stream().filter(p -> p.name().equals(policyName)).findFirst().orElseThrow();
        assertThat(policy.roles()).containsExactlyInAnyOrder("inst:minagri:admin", "inst:minagri:hr");
    }

    @Test
    void subgroupModesDiffer() {
        InstitutionPlan enumerated = FgapProjection.renderInstitution(registry, SLUG, List.of(), List.of(), SUBGROUPS,
                SubgroupMode.ENUMERATE);
        InstitutionPlan propagated = FgapProjection.renderInstitution(registry, SLUG, List.of(), List.of(), SUBGROUPS,
                SubgroupMode.PROPAGATE);
        assertThat(resourcesOf(enumerated, ResourceType.GROUPS)).contains("/minagri/planning");
        assertThat(resourcesOf(propagated, ResourceType.GROUPS)).containsExactly("/minagri");
    }

    @Test
    void sharedActionGateIsRealmWideAndOnlyMapRoles() {
        InstitutionPlan shared = FgapProjection.renderShared(registry, List.of("minagri", "minisante")).orElseThrow();
        ScopePermission p = shared.permissions().get(0);
        assertThat(p.resourceType()).isEqualTo(ResourceType.USERS);
        assertThat(p.resources()).containsExactly(FgapProjection.ALL_RESOURCES);
        assertThat(p.scopes()).containsExactly("map-roles");
        assertThat(p.name()).isEqualTo("inst:_shared:users:map-roles");
        assertThat(shared.policies().get(0).name()).isEqualTo("inst:_shared:map-roles");
        assertThat(shared.policies().get(0).roles()).containsExactly("inst:minagri:admin", "inst:minagri:hr",
                "inst:minisante:admin", "inst:minisante:hr");
    }

    @Test
    void renderAllIncludesExactlyOneSharedPlan() {
        List<InstitutionPlan> plans = FgapProjection.renderAll(registry, List.of("minisante", "minagri", "minagri"),
                ROLES, CLIENTS, List.of(), SubgroupMode.ENUMERATE);
        assertThat(plans).extracting(InstitutionPlan::institution).containsExactly("minagri", "minisante", "_shared");
    }

    @Test
    void noSharedPlanWhenNoTierMapsRoles() {
        DelegatedPermissionRegistry auditorsOnly = DelegatedPermissionsLoader.parse("""
                {"version": 1,
                 "guardrail": {"forbidden_realm_roles": ["admin"]},
                 "assignments": {"default": ["aud"]},
                 "templates": {"aud": {"grant_role": "inst:{slug}:auditor", "capabilities": ["view-members"]}}}
                """, "<t>");
        assertThat(FgapProjection.renderShared(auditorsOnly, List.of("minagri"))).isEmpty();
        assertThat(FgapProjection.renderAll(auditorsOnly, List.of("minagri"), List.of(), List.of(), List.of(),
                SubgroupMode.ENUMERATE)).extracting(InstitutionPlan::institution).containsExactly("minagri");
    }

    @Test
    void allUsersPermissionIsRefusedForAnythingButMapRoles() {
        InstitutionPlan bad = FgapProjection.renderShared(registry, List.of("minagri")).orElseThrow();
        InstitutionPlan doctored = new InstitutionPlan(bad.institution(), bad.policies(), List.of(
                new ScopePermission("inst:_shared:users:manage", "", ResourceType.USERS,
                        List.of(FgapProjection.ALL_RESOURCES), List.of("manage"), bad.permissions().get(0).policies())));
        assertThatThrownBy(() -> FgapProjection.assertPlanSafe(doctored, registry.guardrail()))
                .isInstanceOf(FgapProjectionException.class).hasMessageContaining("only 'map-roles'");
    }

    @Test
    void assertPlanSafeRefusesEveryEscape() {
        var g = registry.guardrail();
        List<String> pol = List.of("p");
        assertThatThrownBy(() -> FgapProjection.assertPlanSafe(plan("minagri", new ScopePermission("x", "",
                ResourceType.ROLES, List.of("inst:minisante:staff"), List.of("map-role"), pol)), g))
                .hasMessage("permission 'x' would grant map-role on 'inst:minisante:staff', outside 'inst:minagri:*'"
                        + " — map-roles scoping depends on it");
        assertThatThrownBy(() -> FgapProjection.assertPlanSafe(plan("minagri", new ScopePermission("x", "",
                ResourceType.CLIENTS, List.of("sso-client"), List.of("view"), pol)), g))
                .hasMessage("permission 'x' would reach client 'sso-client', outside 'minagri-*'");
        assertThatThrownBy(() -> FgapProjection.assertPlanSafe(plan("minagri", new ScopePermission("x", "",
                ResourceType.GROUPS, List.of("/minagri-old"), List.of("view"), pol)), g))
                .hasMessage("permission 'x' would reach group '/minagri-old', outside '/minagri'");
        assertThatThrownBy(() -> FgapProjection.assertPlanSafe(plan("minagri", new ScopePermission("x", "",
                ResourceType.GROUPS, List.of(), List.of("view"), pol)), g))
                .hasMessage("permission 'x' has no resources — it would be a no-op");
        assertThatThrownBy(() -> FgapProjection.assertPlanSafe(plan("minagri", new ScopePermission("x", "",
                ResourceType.GROUPS, List.of("/minagri"), List.of("view"), List.of())), g))
                .hasMessage("permission 'x' has no policy — an unpolicied permission grants everyone");
        // a forbidden role inside the namespace (a guardrail entry named like a namespaced role)
        var guard = new rw.gov.risa.sso.wrapper.policy.delegated.Guardrail(Set.of("inst:minagri:root"), Map.of());
        assertThatThrownBy(() -> FgapProjection.assertPlanSafe(plan("minagri", new ScopePermission("x", "",
                ResourceType.ROLES, List.of("inst:minagri:root"), List.of("map-role"), pol)), guard))
                .hasMessage("permission 'x' would grant map-role on forbidden broad role 'inst:minagri:root'");
        assertThatCode(() -> FgapProjection.assertPlanSafe(plan("minagri", new ScopePermission("x", "",
                ResourceType.GROUPS, List.of("/minagri", "/minagri/a"), List.of("view"), pol)), g))
                .doesNotThrowAnyException();
    }

    private static InstitutionPlan plan(String slug, ScopePermission p) {
        return new InstitutionPlan(slug, List.of(), List.of(p));
    }

    @Test
    void driftIsReportedInTemplateWords() {
        List<Finding> findings = FgapDrift.diff(List.of(plan), Map.of(), Map.of());
        assertThat(findings).anyMatch(f -> f.kind() == FgapDrift.Kind.MISSING_POLICY);
        assertThat(findings).anyMatch(f -> f.kind() == FgapDrift.Kind.MISSING_PERMISSION);

        Map<String, ActualPolicy> policies = new LinkedHashMap<>();
        plan.policies().forEach(p -> policies.put(p.name(), new ActualPolicy(p.roles())));
        Map<String, ActualPermission> permissions = new LinkedHashMap<>();
        plan.permissions().forEach(p -> permissions.put(p.name(),
                new ActualPermission(p.resources(), p.scopes(), p.policies())));
        assertThat(FgapDrift.diff(List.of(plan), policies, permissions)).isEmpty();

        permissions.put("inst:minagri:made-by-hand:groups:manage",
                new ActualPermission(List.of("/minagri"), List.of("manage"), List.of()));
        List<Finding> strays = FgapDrift.diff(List.of(plan), policies, permissions);
        assertThat(strays).extracting(Finding::kind).containsExactly(FgapDrift.Kind.UNMANAGED);
        assertThat(strays.get(0).kind().wire()).isEqualTo("unmanaged");
    }

    @Test
    void driftReportsMismatchesAndIgnoresUnmanagedLookingStrangers() {
        RolePolicy policy = plan.policies().get(0);
        ScopePermission perm = plan.permissions().get(0);
        Map<String, ActualPolicy> policies = new LinkedHashMap<>();
        plan.policies().forEach(p -> policies.put(p.name(), new ActualPolicy(p.roles())));
        policies.put(policy.name(), new ActualPolicy(List.of("inst:minagri:intruder")));
        policies.put("someone-elses-policy", new ActualPolicy(null));
        Map<String, ActualPermission> permissions = new LinkedHashMap<>();
        plan.permissions().forEach(p -> permissions.put(p.name(),
                new ActualPermission(p.resources(), p.scopes(), p.policies())));
        permissions.put(perm.name(), new ActualPermission(perm.resources(), List.of("other"), perm.policies()));
        List<Finding> f = FgapDrift.diff(List.of(plan), policies, permissions);
        assertThat(f).extracting(Finding::toString).containsExactly(
                "[mismatch] " + policy.name() + ": roles: missing " + rw.gov.risa.sso.wrapper.policy.delegated.PyRepr
                        .list(policy.roles().stream().sorted().toList()) + "; unexpected ['inst:minagri:intruder']",
                "[mismatch] " + perm.name() + ": scopes: missing " + rw.gov.risa.sso.wrapper.policy.delegated.PyRepr
                        .list(perm.scopes()) + "; unexpected ['other']");
    }

    @Test
    void missingPermissionDetail() {
        ScopePermission perm = plan.permissions().get(0);
        Finding f = FgapDrift.diff(List.of(plan), Map.of(), Map.of()).stream()
                .filter(x -> x.name().equals(perm.name())).findFirst().orElseThrow();
        assertThat(f.detail()).isEqualTo(perm.resourceType().wire() + " ['" + perm.scopes().get(0) + "'] over "
                + perm.resources().size() + " resource(s)");
    }

    @Test
    void namingIsStructural() {
        assertThat(FgapProjection.policyName("minagri", List.of("b", "a"))).isEqualTo("inst:minagri:a+b");
        assertThat(FgapProjection.permissionName("minagri", List.of("x"), ResourceType.GROUPS, "view"))
                .isEqualTo("inst:minagri:x:groups:view");
        assertThat(FgapProjection.isManagedName("inst:x")).isTrue();
        assertThat(FgapProjection.isManagedName("other")).isFalse();
        assertThat(FgapProjection.isManagedName(null)).isFalse();
        assertThat(FgapProjection.parseManagedName("inst:minagri:institution-admin:groups"))
                .contains(new FgapProjection.ManagedName("minagri", "institution-admin"));
        assertThat(FgapProjection.parseManagedName("inst:minagri")).isEmpty();
        assertThat(FgapProjection.parseManagedName("x:minagri:y")).isEmpty();
    }

    @Test
    void groupScopesIncludeViewAndAreSorted() {
        ScopePermission groups = plan.permissions().stream()
                .filter(p -> p.resourceType() == ResourceType.GROUPS && p.scopes().contains("view")).findFirst()
                .orElseThrow();
        assertThat(groups.resources()).containsExactly("/minagri", "/minagri/planning");
        // every tier in the shipped config has view-members, so view is granted to all three
        assertThat(groups.policies()).containsExactly(
                "inst:minagri:institution-admin+institution-auditor+institution-hr");
    }

    @Test
    void clientViewIsGrantedByManageToo() {
        Set<String> viewTiers = plan.permissions().stream()
                .filter(p -> p.resourceType() == ResourceType.CLIENTS && p.scopes().contains("view"))
                .flatMap(p -> p.policies().stream()).collect(Collectors.toSet());
        assertThat(viewTiers).containsExactly("inst:minagri:institution-admin+institution-auditor");
        Set<String> manageTiers = plan.permissions().stream()
                .filter(p -> p.resourceType() == ResourceType.CLIENTS && p.scopes().contains("manage"))
                .flatMap(p -> p.policies().stream()).collect(Collectors.toSet());
        assertThat(manageTiers).containsExactly("inst:minagri:institution-admin");
    }

    @Test
    void descriptionsUsePythonReprs() {
        RolePolicy p = plan.policies().stream()
                .filter(x -> x.name().equals("inst:minagri:institution-admin+institution-hr")).findFirst().orElseThrow();
        assertThat(p.description()).isEqualTo("Holders of ['inst:minagri:admin', 'inst:minagri:hr'] in 'minagri' "
                + "(tiers: ['institution-admin', 'institution-hr']). Projected from "
                + "configs/delegated-permissions/templates.json; do not edit by hand.");
        ScopePermission perm = plan.permissions().stream()
                .filter(x -> x.name().equals("inst:minagri:institution-admin:clients:manage")).findFirst().orElseThrow();
        assertThat(perm.description()).isEqualTo("'minagri': manage on 1 Clients for tiers ['institution-admin'].");
        assertThat(plan.describe().get("object_count")).isEqualTo(plan.policies().size() + plan.permissions().size());
    }

    @Test
    void aWithheldInstitutionProducesNoObjectsAndAnUnknownOneUsesTheDefault() {
        DelegatedPermissionRegistry withheld = DelegatedPermissionsLoader.parse("""
                {"version": 1,
                 "guardrail": {"forbidden_realm_roles": ["admin"]},
                 "assignments": {"default": ["a"], "by_institution": {"rdb": []}},
                 "templates": {"a": {"grant_role": "inst:{slug}:admin", "capabilities": ["view-members"]}}}
                """, "<t>");
        InstitutionPlan rdb = FgapProjection.renderInstitution(withheld, "rdb");
        assertThat(rdb.policies()).isEmpty();
        assertThat(rdb.permissions()).isEmpty();
        assertThat(FgapProjection.renderInstitution(withheld, "rra").permissions()).hasSize(2);
    }
}
