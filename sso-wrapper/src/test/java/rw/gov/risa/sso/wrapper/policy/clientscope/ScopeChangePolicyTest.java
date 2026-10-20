package rw.gov.risa.sso.wrapper.policy.clientscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import rw.gov.risa.sso.wrapper.policy.clientscope.ScopeChangePolicy.InstitutionAccess;
import rw.gov.risa.sso.wrapper.policy.clientscope.ScopeChangePolicy.Plan;

/** The 409-before-removal rule of PUT /client/clients/{id}/scope, and the delegated cross-cutting refusals. */
class ScopeChangePolicyTest {

    static final List<String> SLUGS = List.of("minagri", "minisante", "rdb");

    @Test
    void wideningNeedsNoConfirmation() {
        Plan p = ScopeChangePolicy.evaluate(ClientScope.forSelected("c", List.of("minagri")),
                ClientScope.forAllInstitutions("c"), SLUGS, Map.of(), false);
        assertThat(p.removedInstitutions()).isEmpty();
        assertThat(p.confirmationRequired()).isFalse();
    }

    @Test
    void removingAnInstitutionWithUsersIs409First() {
        Plan p = ScopeChangePolicy.evaluate(ClientScope.forSelected("c", List.of("minagri", "rdb")),
                ClientScope.forSelected("c", List.of("minagri")), SLUGS,
                Map.of("rdb", new InstitutionAccess("rdb", false, 3)), false);
        assertThat(p.confirmationRequired()).isTrue();
        assertThat(p.removedInstitutions()).containsExactly("rdb");
        assertThat(p.accessAtRisk()).extracting(InstitutionAccess::users).containsExactly(3);
        Map<String, Object> details = p.conflictDetails();
        assertThat(details).containsKeys("message", "removed_institutions", "access_at_risk", "current_scope",
                "requested_scope");
        assertThat(details.get("removed_institutions")).isEqualTo(List.of("rdb"));
        assertThat((String) details.get("message")).contains("confirm=true");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) details.get("access_at_risk");
        assertThat(rows.get(0)).containsEntry("institution", "rdb").containsEntry("group_assigned", false)
                .containsEntry("users", 3);
    }

    @Test
    void aGroupAssignmentAloneIsAtRisk() {
        Plan p = ScopeChangePolicy.evaluate(ClientScope.forInstitution("c", "minagri"),
                ClientScope.forInstitution("c", "rdb"), SLUGS,
                Map.of("minagri", new InstitutionAccess("minagri", true, 0)), false);
        assertThat(p.confirmationRequired()).isTrue();
    }

    @Test
    void confirmingAppliesItAndStillListsTheRemovedInstitutionsToWithdraw() {
        Plan p = ScopeChangePolicy.evaluate(ClientScope.forSelected("c", List.of("minagri", "rdb")),
                ClientScope.forSelected("c", List.of("minagri")), SLUGS,
                Map.of("rdb", new InstitutionAccess("rdb", true, 2)), true);
        assertThat(p.confirmationRequired()).isFalse();
        assertThat(p.removedInstitutions()).containsExactly("rdb");
    }

    @Test
    void removingAnInstitutionWithNobodyOnItNeedsNoConfirmation() {
        Plan p = ScopeChangePolicy.evaluate(ClientScope.forSelected("c", List.of("minagri", "rdb")),
                ClientScope.forSelected("c", List.of("minagri")), SLUGS,
                Map.of("rdb", new InstitutionAccess("rdb", false, 0)), false);
        assertThat(p.confirmationRequired()).isFalse();
        assertThat(p.accessAtRisk()).isEmpty();
        assertThat(p.removedInstitutions()).containsExactly("rdb");
    }

    @Test
    void narrowingFromAllWarnsAboutEveryInstitutionThatDropsOut() {
        Plan p = ScopeChangePolicy.evaluate(ClientScope.forAllInstitutions("c"),
                ClientScope.forSelected("c", List.of("minagri")), SLUGS,
                Map.of("minisante", new InstitutionAccess("minisante", true, 0),
                        "rdb", new InstitutionAccess("rdb", false, 1),
                        "minagri", new InstitutionAccess("minagri", true, 9)), false);
        assertThat(p.removedInstitutions()).containsExactly("minisante", "rdb");
        assertThat(p.accessAtRisk()).extracting(InstitutionAccess::institution).containsExactly("minisante", "rdb");
    }

    @Test
    void anOwnedClientCanBeMadeCrossCuttingWithoutLoss() {
        Plan p = ScopeChangePolicy.evaluate(ClientScope.forInstitution("c", "minagri"),
                ClientScope.forSelected("c", List.of("minagri", "rdb")), SLUGS, null, false);
        assertThat(p.confirmationRequired()).isFalse();
        assertThat(ScopeChangePolicy.removedInstitutions(ClientScope.forInstitution("c", "minagri"),
                ClientScope.forSelected("c", List.of("minagri", "rdb")), SLUGS)).isEmpty();
    }

    @Test
    void delegatedAdminsAreRefusedPlatformDecisionsOnCrossCuttingApps() {
        ClientScope shared = ClientScope.forAllInstitutions("national-payroll");
        assertThat(DelegatedAppRules.crossCuttingRefusal(shared, DelegatedAppRules.Operation.JOIN_COLUMN))
                .hasValueSatisfying(m -> assertThat(m).contains("cross-cutting").contains("identifier"));
        assertThat(DelegatedAppRules.crossCuttingRefusal(shared, DelegatedAppRules.Operation.MFA))
                .hasValueSatisfying(m -> assertThat(m).contains("TOTP"));
        assertThat(DelegatedAppRules.crossCuttingRefusal(shared, DelegatedAppRules.Operation.SECRET))
                .hasValueSatisfying(m -> assertThat(m).contains("secret belongs to the platform"));
        for (DelegatedAppRules.Operation op : DelegatedAppRules.Operation.values()) {
            assertThat(DelegatedAppRules.crossCuttingRefusal(ClientScope.forInstitution("minagri-x", "minagri"), op))
                    .isEmpty();
        }
    }
}
