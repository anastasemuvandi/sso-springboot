package rw.gov.risa.sso.wrapper.policy.delegated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedAudit.Holder;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedAudit.HolderRoles;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedAudit.Report;

/** Port of the pure decisions in tests/e2e/test_delegated_permissions_service.py. */
class DelegatedOperationsTest {

    private static final Set<String> PLATFORM = Set.of("super_admin", "admin", "system_admin");
    private final DelegatedPermissionRegistry registry = Fixtures.serviceRegistry();

    @Nested
    class Apply {
        @Test
        void plansEveryRoleTheEnabledTiersNeed() {
            RoleProvisioningPlan plan = RoleProvisioningPlan.plan(registry, List.of("minagri"), List.of());
            assertThat(plan.items()).extracting(RoleProvisioningPlan.Item::role)
                    .containsExactlyInAnyOrder("inst:minagri:admin", "inst:minagri:staff", "inst:minagri:hr");
            assertThat(plan.items()).allMatch(i -> i.action() == RoleProvisioningPlan.Action.CREATE);
        }

        @Test
        void onlyMissingRolesAreCreated() {
            RoleProvisioningPlan plan = RoleProvisioningPlan.plan(registry, List.of("minagri"),
                    List.of("inst:minagri:admin"));
            assertThat(plan.toCreate()).containsExactlyInAnyOrder("inst:minagri:staff", "inst:minagri:hr");
            assertThat(plan.unchanged()).containsExactly("inst:minagri:admin");
            assertThat(RoleProvisioningPlan.Action.EXISTS.wire()).isEqualTo("exists");
        }

        @Test
        void isIdempotentAndDedupesSharedShortRoles() {
            RoleProvisioningPlan plan = RoleProvisioningPlan.plan(registry, List.of("minagri"),
                    List.of("inst:minagri:admin", "inst:minagri:hr", "inst:minagri:staff"));
            assertThat(plan.toCreate()).isEmpty();
            assertThat(plan.unchanged()).hasSize(3);
            // staff is provisioned by both tiers; first wins
            assertThat(plan.items()).filteredOn(i -> i.role().equals("inst:minagri:staff"))
                    .singleElement().extracting(RoleProvisioningPlan.Item::template).isEqualTo("local-admin");
        }

        @Test
        void aWithheldTierIsNotProvisioned() {
            RoleProvisioningPlan plan = RoleProvisioningPlan.plan(registry, List.of("mineduc"), List.of());
            assertThat(plan.items()).extracting(RoleProvisioningPlan.Item::role)
                    .containsExactlyInAnyOrder("inst:mineduc:hr", "inst:mineduc:staff");
        }

        @Test
        void anInvalidSlugIsRefused() {
            assertThatThrownBy(() -> RoleProvisioningPlan.plan(registry, List.of("MINAGRI"), List.of()))
                    .isInstanceOf(DelegatedPermissionConfigException.class);
        }

        @Test
        void roleDescription() {
            RoleProvisioningPlan.Item item = new RoleProvisioningPlan.Item("minagri", "local-admin",
                    "inst:minagri:admin", RoleProvisioningPlan.Action.CREATE);
            assertThat(RoleProvisioningPlan.roleDescription(item, "configs/x.json"))
                    .isEqualTo("Delegated permissions: local-admin for minagri (managed by configs/x.json)");
        }
    }

    @Nested
    class Preview {
        @Test
        void listsEachTierAndTheEffectiveCeiling() {
            InstitutionPreview p = InstitutionPreview.of(registry, "minagri");
            assertThat(p.institution()).isEqualTo("minagri");
            assertThat(p.templates()).extracting(InstitutionPreview.Tier::name)
                    .containsExactly("local-admin", "local-hr");
            InstitutionPreview.Tier admin = p.templates().get(0);
            assertThat(admin.grantRole()).isEqualTo("inst:minagri:admin");
            assertThat(admin.realmRoles()).contains("inst:minagri:staff");
            assertThat(admin.displayName()).isEqualTo("local-admin");
            assertThat(p.effectiveGrant().roleAllow()).containsExactly("inst:minagri:*", "inst:minagri:staff");
            assertThat(p.effectiveGrant().groupPath()).isEqualTo("/minagri");
        }

        @Test
        void aWithheldTierIsAbsentFromThePreview() {
            InstitutionPreview p = InstitutionPreview.of(registry, "mineduc");
            assertThat(p.templates()).extracting(InstitutionPreview.Tier::name).containsExactly("local-hr");
            assertThat(p.effectiveGrant().capabilityNames()).doesNotContain("map-roles");
        }
    }

    private Report audit(List<String> realmRoles, Map<String, List<Holder>> members,
                         Map<String, HolderRoles> roles, List<String> institutions) {
        return DelegatedAudit.audit(registry, DelegatedAudit.staticView(realmRoles, members, roles), institutions,
                PLATFORM);
    }

    private static HolderRoles realm(String... roles) {
        return new HolderRoles(List.of(roles), Map.of());
    }

    private static final List<String> MINAGRI_ROLES = List.of("inst:minagri:admin", "inst:minagri:hr",
            "inst:minagri:staff");

    @Nested
    class Audit {
        @Test
        void aProperlyProvisionedInstitutionIsClean() {
            Report r = audit(MINAGRI_ROLES, Map.of("inst:minagri:admin", List.of(new Holder("u1", "minagri.admin"))),
                    Map.of("u1", realm("inst:minagri:admin", "employee")), List.of("minagri"));
            assertThat(r.clean()).isTrue();
            assertThat(r.guardrailViolations()).isEmpty();
            assertThat(r.missingRoles()).isEmpty();
            assertThat(r.unmanagedGrants()).isEmpty();
        }

        @Test
        void holdersAreReportedPerTier() {
            Report r = audit(MINAGRI_ROLES, Map.of(
                            "inst:minagri:admin", List.of(new Holder("u1", "minagri.admin")),
                            "inst:minagri:hr", List.of(new Holder("u2", "minagri.hr"))),
                    Map.of("u1", realm("inst:minagri:admin"), "u2", realm("inst:minagri:hr")), List.of("minagri"));
            List<DelegatedAudit.Tier> tiers = r.institutions().get(0).tiers();
            assertThat(tiers.get(0).template()).isEqualTo("local-admin");
            assertThat(tiers.get(0).holders()).extracting(DelegatedAudit.TierHolder::username)
                    .containsExactly("minagri.admin");
            assertThat(tiers.get(1).holders()).extracting(DelegatedAudit.TierHolder::username)
                    .containsExactly("minagri.hr");
            assertThat(tiers).flatExtracting(DelegatedAudit.Tier::holders)
                    .allMatch(DelegatedAudit.TierHolder::guardrailOk);
            assertThat(tiers.get(0).grantRoleExists()).isTrue();
            assertThat(tiers.get(0).capabilities()).containsExactly("manage-members", "map-roles", "view-members");
        }

        @Test
        void aForbiddenRealmRoleIsAViolation() {
            Report r = audit(MINAGRI_ROLES, Map.of("inst:minagri:admin", List.of(new Holder("u1", "sneaky"))),
                    Map.of("u1", realm("inst:minagri:admin", "manage-users")), List.of("minagri"));
            assertThat(r.clean()).isFalse();
            assertThat(r.guardrailViolations()).hasSize(1);
            DelegatedAudit.Violation v = r.guardrailViolations().get(0);
            assertThat(v.username()).isEqualTo("sneaky");
            assertThat(v.forbidden()).extracting(GuardrailViolation::role).containsExactly("manage-users");
            assertThat(v.isPlatformAdmin()).isFalse();
        }

        @Test
        void aRealmManagementClientRoleIsAViolation() {
            Report r = audit(MINAGRI_ROLES, Map.of("inst:minagri:admin", List.of(new Holder("u1", "leaky"))),
                    Map.of("u1", new HolderRoles(List.of("inst:minagri:admin"),
                            Map.of("realm-management", List.of("view-users")))), List.of("minagri"));
            GuardrailViolation f = r.guardrailViolations().get(0).forbidden().get(0);
            assertThat(f.client()).isEqualTo("realm-management");
            assertThat(f.kind().wire()).isEqualTo("client-role");
        }

        @Test
        void aBroadRoleInheritedViaACompositeIsCaught() {
            Report r = audit(MINAGRI_ROLES, Map.of("inst:minagri:admin", List.of(new Holder("u1", "composite"))),
                    Map.of("u1", realm("inst:minagri:admin", "admin")), List.of("minagri"));
            assertThat(r.guardrailViolations().get(0).forbidden()).extracting(GuardrailViolation::role)
                    .containsExactly("admin");
        }

        @Test
        void aPlatformAdminHoldingAnInstRoleIsFlaggedAsSuch() {
            Report r = audit(MINAGRI_ROLES, Map.of("inst:minagri:admin", List.of(new Holder("u1", "platform.admin"))),
                    Map.of("u1", realm("inst:minagri:admin", "super_admin")), List.of("minagri"));
            assertThat(r.guardrailViolations().get(0).isPlatformAdmin()).isTrue();
        }

        @Test
        void aViolatingAccountIsReportedOnceListingEveryTier() {
            Report r = audit(MINAGRI_ROLES, Map.of(
                            "inst:minagri:admin", List.of(new Holder("u1", "dual")),
                            "inst:minagri:hr", List.of(new Holder("u1", "dual"))),
                    Map.of("u1", realm("inst:minagri:admin", "inst:minagri:hr", "admin")), List.of("minagri"));
            assertThat(r.guardrailViolations()).extracting(DelegatedAudit.Violation::username).containsExactly("dual");
            DelegatedAudit.Violation v = r.guardrailViolations().get(0);
            assertThat(v.grantRoles()).containsExactlyInAnyOrder("inst:minagri:admin", "inst:minagri:hr");
            assertThat(v.templates()).containsExactlyInAnyOrder("local-admin", "local-hr");
        }

        @Test
        void theSameAccountInTwoInstitutionsIsTwoFindings() {
            Report r = audit(List.of("inst:minagri:admin", "inst:minagri:hr", "inst:minagri:staff",
                            "inst:minisante:admin", "inst:minisante:hr", "inst:minisante:staff"),
                    Map.of("inst:minagri:admin", List.of(new Holder("u1", "dual")),
                            "inst:minisante:admin", List.of(new Holder("u1", "dual"))),
                    Map.of("u1", realm("inst:minagri:admin", "inst:minisante:admin", "admin")),
                    List.of("minisante", "minagri"));
            assertThat(r.guardrailViolations()).extracting(DelegatedAudit.Violation::institution)
                    .containsExactly("minagri", "minisante");
        }

        @Test
        void aMissingRoleIsReported() {
            Report r = audit(List.of("inst:minagri:admin"), Map.of(), Map.of(), List.of("minagri"));
            assertThat(r.clean()).isFalse();
            assertThat(r.missingRoles()).extracting(DelegatedAudit.MissingRole::role)
                    .containsExactlyInAnyOrder("inst:minagri:hr", "inst:minagri:staff", "inst:minagri:staff");
        }

        @Test
        void aGrantRoleForAWithheldTierIsUnmanaged() {
            Report r = audit(List.of("inst:mineduc:admin", "inst:mineduc:hr", "inst:mineduc:staff"), Map.of(),
                    Map.of(), List.of("mineduc"));
            assertThat(r.unmanagedGrants()).extracting(DelegatedAudit.UnmanagedGrant::role)
                    .containsExactly("inst:mineduc:admin");
            assertThat(r.unmanagedGrants().get(0).reason()).isEqualTo(
                    "template 'local-admin' is not enabled for 'mineduc' but its grant role exists in the realm");
            assertThat(r.clean()).isFalse();
        }

        @Test
        void institutionsAreDiscoveredFromTheRealmsGrantRoles() {
            Report r = audit(List.of("inst:minagri:admin", "inst:minagri:hr", "inst:minagri:staff",
                    "inst:minisante:hr", "inst:minisante:staff", "unrelated"), Map.of(), Map.of(), null);
            assertThat(r.institutions()).extracting(DelegatedAudit.InstitutionAudit::institution)
                    .containsExactly("minagri", "mineduc", "minisante");
            assertThat(r.institutions()).filteredOn(i -> i.institution().equals("mineduc")).singleElement()
                    .extracting(DelegatedAudit.InstitutionAudit::configured).isEqualTo(true);
        }

        @Test
        void theReportNamesTheConfigItJudgedAgainst() {
            assertThat(audit(List.of(), Map.of(), Map.of(), List.of("minagri")).source()).isEqualTo("<test>");
        }

        @Test
        void aHolderWithoutUsernameFallsBackToTheId() {
            Report r = audit(MINAGRI_ROLES, Map.of("inst:minagri:admin", List.of(new Holder("u1", null))),
                    Map.of("u1", realm()), List.of("minagri"));
            assertThat(r.institutions().get(0).tiers().get(0).holders().get(0).username()).isEqualTo("u1");
        }
    }
}
