package rw.gov.risa.sso.wrapper.policy.institutioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.delegated.TestRepo;

/**
 * Ports of the reference's TestInstitutionContextEnforcement, TestRequireInstitutionAdmin and
 * TestGuardrailSeesTokenClientRoles (pure parts), against the SHIPPED templates.json.
 */
class InstitutionContextShippedConfigTest {

    static DelegatedPermissionRegistry registry;
    static InstitutionContextResolver resolver;

    @BeforeAll
    static void load() {
        registry = DelegatedPermissionsLoader.loadFrom(TestRepo.path("configs/delegated-permissions/templates.json"));
        resolver = new InstitutionContextResolver(GrantLookup.of(registry), InstitutionContextSettings.defaults());
    }

    static CallerIdentity user(List<String> realmRoles, Map<String, List<String>> clientRoles) {
        return new CallerIdentity("tester", "uid-1", realmRoles, clientRoles, null);
    }

    static CallerIdentity user(String... realmRoles) {
        return user(List.of(realmRoles), Map.of());
    }

    static InstitutionContext ctx(String... roles) {
        return resolver.resolveInstitutionAdmin(user(roles), null).orThrow();
    }

    static Refusal refused(CallerIdentity c, String institution) {
        Resolution<InstitutionContext> r = resolver.resolveInstitutionAdmin(c, institution);
        assertThat(r).isInstanceOf(Resolution.Refused.class);
        return ((Resolution.Refused<InstitutionContext>) r).refusal();
    }

    // ---- TestInstitutionContextEnforcement ----

    @Test
    void capabilityMissingIs403() {
        InstitutionContext c = ctx("inst:minagri:hr");
        assertThatThrownBy(() -> c.assertCapability(Capability.MANAGE_CLIENTS))
                .isInstanceOf(InstitutionRefusedException.class)
                .hasMessageContaining("manage-clients")
                .satisfies(e -> assertThat(((InstitutionRefusedException) e).refusal().status()).isEqualTo(403));
    }

    @Test
    void capabilityPresentPasses() {
        InstitutionContext c = ctx("inst:minagri:admin");
        c.assertCapability(Capability.MANAGE_CLIENTS);
        assertThat(c.capabilities()).contains("manage-clients");
    }

    @Test
    void roleOutsideNamespaceIs403() {
        InstitutionContext c = ctx("inst:minagri:admin");
        for (String role : List.of("super_admin", "manage-users", "inst:minisante:staff")) {
            assertThatThrownBy(() -> c.assertRoleInNamespace(role)).isInstanceOf(InstitutionRefusedException.class);
        }
    }

    @Test
    void deniedRoleIs403EvenInsideNamespace() {
        InstitutionContext c = ctx("inst:minagri:admin");
        assertThatThrownBy(() -> c.assertRoleInNamespace("inst:minagri:admin"))
                .isInstanceOf(InstitutionRefusedException.class);
        c.assertRoleInNamespace("inst:minagri:staff");
    }

    @Test
    void readOnlyTierIsStoppedByCapabilityBeforePatterns() {
        InstitutionContext c = ctx("inst:minagri:auditor");
        assertThat(c.administered()).containsExactly("minagri");
        assertThatThrownBy(() -> c.assertRoleInNamespace("inst:minagri:staff"))
                .hasMessageContaining("map-roles");
    }

    @Test
    void hrTierMayMapOnlyStaff() {
        InstitutionContext c = ctx("inst:minagri:hr");
        assertThat(c.checkRoleInNamespace("inst:minagri:staff")).isEmpty();
        assertThat(c.checkRoleInNamespace("inst:minagri:hr")).isPresent();
    }

    @Test
    void clientOutsideScopeIs403() {
        InstitutionContext c = ctx("inst:minagri:admin");
        assertThatThrownBy(() -> c.assertClientInScope("minisante-hmis", null))
                .isInstanceOf(InstitutionRefusedException.class);
        c.assertClientInScope("minagri-kubaka-web", null);
    }

    @Test
    void crossCuttingClientInScopeIsReachable() {
        InstitutionContext c = ctx("inst:minagri:admin");
        c.assertClientInScope("national-payroll", ClientScope.forSelected("national-payroll", List.of("minagri", "rdb")));
        assertThatThrownBy(() -> c.assertClientInScope("national-payroll",
                ClientScope.forSelected("national-payroll", List.of("rdb"))))
                .hasMessageContaining("do not include 'minagri'");
    }

    @Test
    void groupPathComesFromTemplate() {
        assertThat(ctx("inst:minagri:admin").groupPath()).isEqualTo("/minagri");
    }

    @Test
    void tiersAddUp() {
        InstitutionContext c = ctx("inst:minagri:hr", "inst:minagri:auditor");
        assertThat(c.capabilities()).containsExactly("manage-members", "manage-membership", "map-roles",
                "view-clients", "view-members");
        assertThat(c.checkClientInScope("minagri-x", null, Capability.VIEW_CLIENTS)).isEmpty();
        assertThat(c.checkClientInScope("minagri-x", null, Capability.MANAGE_CLIENTS)).isPresent();
    }

    // ---- TestRequireInstitutionAdmin ----

    @Test
    void localAdminResolvesToInstitution() {
        InstitutionContext c = ctx("inst:minagri:admin");
        assertThat(c.institution()).isEqualTo("minagri");
        assertThat(c.platformAdmin()).isFalse();
        assertThat(c.capabilities()).contains("map-roles");
    }

    @Test
    void broadClientRoleAlongsideGrantIsRefused() {
        Refusal r = refused(user(List.of("inst:minagri:admin"), Map.of("realm-management", List.of("manage-users"))), null);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.message()).contains("Guardrail").contains("manage-users");
    }

    @Test
    void readOnlyRealmManagementRoleIsAlsoRefused() {
        Refusal r = refused(user(List.of("inst:minagri:admin"), Map.of("realm-management", List.of("view-users"))), null);
        assertThat(r.message()).contains("Guardrail");
    }

    @Test
    void forbiddenRealmRoleAlongsideGrantIsRefused() {
        assertThat(refused(user("inst:minagri:admin", "realm_admin"), null).message()).contains("Guardrail");
    }

    @Test
    void guardrailCanBeDowngradedToLogLine() {
        var lax = new InstitutionContextResolver(GrantLookup.of(registry), new InstitutionContextSettings(false, true));
        var r = lax.resolveInstitutionAdmin(
                user(List.of("inst:minagri:admin"), Map.of("realm-management", List.of("manage-users"))), null);
        assertThat(r.orThrow().institution()).isEqualTo("minagri");
        assertThat(r.warnings()).isNotEmpty();
    }

    @Test
    void platformAdminIsExempt() {
        InstitutionContext c = resolver.resolveInstitutionAdmin(user("super_admin"), "minagri").orThrow();
        assertThat(c.platformAdmin()).isTrue();
        assertThat(c.capabilities()).hasSize(6);
    }

    // ---- TestGuardrailSeesTokenClientRoles (pure parts) ----

    @Test
    void cleanLocalAdminWithOtherClientRolesStillResolves() {
        InstitutionContext c = resolver.resolveInstitutionAdmin(user(List.of("inst:minagri:admin"),
                Map.of("minagri-kubaka-web", List.of("viewer"), "account", List.of("view-profile"))), null).orThrow();
        assertThat(c.institution()).isEqualTo("minagri");
        assertThat(c.grant().mayMapRole("super_admin")).isFalse();
    }

    @Test
    void callerWithNoGrantIsToldThatNotLectured() {
        Refusal r = refused(user(List.of(), Map.of("realm-management", List.of("manage-users"))), null);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.message()).contains("not an institution administrator").doesNotContain("Guardrail");
    }

    @Test
    void namingAnotherInstitutionIsRefused() {
        Refusal r = refused(user("inst:minagri:admin"), "minisante");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.message()).contains("not an administrator of institution");
    }

    @Test
    void severalInstitutionsMustBeDisambiguated() {
        Refusal r = refused(user("inst:minagri:admin", "inst:minisante:admin"), null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.message()).contains("?institution=");
    }

    @Test
    void platformAdminNamingNothingIsAskedToChoose() {
        assertThat(refused(user("super_admin"), null).status()).isEqualTo(400);
    }
}
