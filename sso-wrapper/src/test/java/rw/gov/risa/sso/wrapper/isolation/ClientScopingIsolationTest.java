package rw.gov.risa.sso.wrapper.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminMvcSupport;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/** SPEC §7.1 — an admin of A cannot see or manage B's clients, nor the control plane. */
class ClientScopingIsolationTest extends InstitutionAdminMvcSupport {

    @Test
    void listingAppsShowsOnlyMyInstitutionsClientsAndHidesTheControlPlane() throws Exception {
        mvc.perform(get("/institution-admin/apps").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].clientId", containsInAnyOrder("minagri-kubaka-web", "gov-portal")));
        mvc.perform(get("/institution-admin/apps").with(TestCallers.institutionAdmin(B, "admin")))
                .andExpect(jsonPath("$[*].clientId", containsInAnyOrder("minisante-hmis", "gov-portal", "gov-health")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"minisante-hmis", "sso-admin-ui"})
    void grantingOrRevokingAccessOnSomeoneElsesClientIs403(String clientId) throws Exception {
        mvc.perform(post("/institution-admin/apps/" + clientId + "/access").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("client_out_of_scope"));
        mvc.perform(delete("/institution-admin/apps/" + clientId + "/access").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
        verify(policyStore, never()).apply(any(), anyString(), anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"minisante-hmis", "sso-admin-ui"})
    void anotherInstitutionsSecretUsersAndJoinColumnAre403(String clientId) throws Exception {
        mvc.perform(get("/institution-admin/apps/" + clientId + "/secret").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/institution-admin/apps/" + clientId + "/users").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/institution-admin/apps/" + clientId + "/join-column").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/institution-admin/apps/" + clientId + "/mfa").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"required\":true}"))
                .andExpect(status().isForbidden());
        verify(kc, never()).clientSecret(any(), anyString());
    }

    @Test
    void grantingMyOwnClientWorksAndUsesMyGroup() throws Exception {
        policies.remove(uuid("minagri-kubaka-web"));
        mvc.perform(post("/institution-admin/apps/minagri-kubaka-web/access").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk());
        assertThat(assignedGroups("minagri-kubaka-web")).containsExactly("g-minagri");
    }

    @Test
    void aReadOnlyTierSeesAppsButCannotChangeAccess() throws Exception {
        mvc.perform(get("/institution-admin/apps").with(TestCallers.institutionAdmin(A, "auditor")))
                .andExpect(status().isOk());
        mvc.perform(post("/institution-admin/apps/minagri-kubaka-web/access").with(TestCallers.institutionAdmin(A, "auditor")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anHrTierHasNoClientScopeAtAll() throws Exception {
        mvc.perform(get("/institution-admin/apps/minagri-kubaka-web/join-column").with(TestCallers.institutionAdmin(A, "hr")))
                .andExpect(status().isForbidden());
    }

    @Test
    void creatingAnAppNamingAnotherInstitutionIsRefused() throws Exception {
        mvc.perform(post("/institution-admin/apps").param("institution", B).with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"app\":\"portal\",\"redirect_uris\":[\"https://a/cb\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("institution_not_administered"));
        mvc.perform(post("/institution-admin/apps").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"app\":\"portal\",\"redirect_uris\":[\"https://a/cb\"],\"institution\":\"minisante\"}"))
                .andExpect(status().isBadRequest());
        verify(kc, never()).createClient(any(), any());
    }

    @Test
    void bsAdminCreatesInsideBsNamespace() throws Exception {
        mvc.perform(post("/institution-admin/apps").with(TestCallers.institutionAdmin(B, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"app\":\"lab\",\"redirect_uris\":[\"https://b/cb\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").value("minisante-lab"))
                .andExpect(jsonPath("$.scope.owner_institution").value(B));
    }
}
