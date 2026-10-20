package rw.gov.risa.sso.wrapper.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;

import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminMvcSupport;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessPolicy;
import rw.gov.risa.sso.wrapper.policy.clientaccess.CanonicalPolicies;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/**
 * SPEC §7.1 — sharing an app between institutions without sharing anything else: no reach beyond the
 * client's recorded scope, no collateral damage to the other institutions on it, and no platform-only
 * operation (secret, join column, MFA) for one institution.
 */
class CrossCuttingClientScopeIsolationTest extends InstitutionAdminMvcSupport {

    @Test
    void aCrossCuttingClientInMyScopeIsListedButNotOwned() throws Exception {
        mvc.perform(get("/institution-admin/apps").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(jsonPath("$[?(@.clientId=='gov-portal')].owned").value(false))
                .andExpect(jsonPath("$[?(@.clientId=='gov-portal')].manageable").value(true));
    }

    @Test
    void aCrossCuttingClientScopedToAnotherInstitutionIsInvisibleAndUntouchable() throws Exception {
        mvc.perform(get("/institution-admin/apps").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(jsonPath("$[*].clientId", not(hasItem("gov-health"))));
        mvc.perform(post("/institution-admin/apps/gov-health/access").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("do not include")));
        mvc.perform(delete("/institution-admin/apps/gov-health/access").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
        verify(policyStore, never()).apply(any(), anyString(), anyList());
    }

    @Test
    void grantingMyAccessOnASharedClientKeepsEveryoneElses() throws Exception {
        policies.put(uuid("gov-portal"), new java.util.ArrayList<>(List.of(
                AccessPolicy.group("p4", CanonicalPolicies.GROUPS, false, List.of("g-minisante")),
                AccessPolicy.user("p6", CanonicalPolicies.USERS, false, List.of("u-b1")))));
        mvc.perform(post("/institution-admin/apps/gov-portal/access").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk());
        assertThat(assignedGroups("gov-portal")).containsExactlyInAnyOrder("g-minisante", "g-minagri");
        assertThat(named("gov-portal")).containsExactly("u-b1");
    }

    @Test
    void anAlreadyGrantedSharedClientIsNotRewritten() throws Exception {
        mvc.perform(post("/institution-admin/apps/gov-portal/access").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk());
        assertThat(assignedGroups("gov-portal")).containsExactly("g-minagri", "g-minisante");
    }

    @Test
    void revokingMyAccessOnASharedClientRemovesOnlyMine() throws Exception {
        policies.get(uuid("gov-portal")).add(AccessPolicy.user("p6", CanonicalPolicies.USERS, false, List.of("u-b1", "u-a1")));
        mvc.perform(delete("/institution-admin/apps/gov-portal/access").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk());
        assertThat(assignedGroups("gov-portal")).containsExactly("g-minisante");
        assertThat(named("gov-portal")).containsExactly("u-b1");
    }

    @Test
    void revokingAlsoRemovesMyOwnIndividuallyAssignedUsers() throws Exception {
        mvc.perform(delete("/institution-admin/apps/minagri-kubaka-web/access").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk());
        assertThat(assignedGroups("minagri-kubaka-web")).isEmpty();
        assertThat(named("minagri-kubaka-web")).isEmpty();
    }

    @Test
    void aTierWithoutManageClientsCannotAssignASharedClient() throws Exception {
        mvc.perform(post("/institution-admin/apps/gov-portal/access").with(TestCallers.institutionAdmin(A, "auditor")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSharedClientsSecretJoinColumnAndMfaAreNotMineToSet() throws Exception {
        mvc.perform(get("/institution-admin/apps/gov-portal/secret").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cross-cutting")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/institution-admin/apps/gov-portal/mfa").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"required\":true}"))
                .andExpect(status().isForbidden());
        verify(kc, never()).clientSecret(any(), anyString());
    }

    @Test
    void aSharedClientsPoliciesAreReadAndWrittenWithTheServiceAccount() throws Exception {
        mvc.perform(post("/institution-admin/apps/gov-portal/access").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk());
        // execFor(caller, crossCutting=true) hands the service account to the policy store
        verify(executors, org.mockito.Mockito.atLeastOnce()).serviceAccount();
    }
}
