package rw.gov.risa.sso.wrapper.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

/**
 * SPEC §5.13 / §7.1–7.2 — revoking access across the boundary: another institution's person or app is a
 * 403 for the WHOLE request with nothing changed; on a shared app only my people are touched; the deny
 * entry beats the group grant and the person stays in their institution group.
 */
class AccessRevocationIsolationTest extends InstitutionAdminMvcSupport {

    private void nothingChanged() {
        verify(policyStore, never()).apply(any(), anyString(), anyList());
    }

    @Test
    void revokingAUserOutsideMyInstitutionIs403() throws Exception {
        mvc.perform(post("/institution-admin/access/revoke").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_ids\":[\"u-b1\"],\"client_ids\":[\"gov-portal\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("not_institution_member"));
        nothingChanged();
    }

    @Test
    void oneOutsideUserRefusesTheWholeBatch() throws Exception {
        mvc.perform(post("/institution-admin/access/revoke").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_ids\":[\"u-a1\",\"u-a2\",\"u-b1\"],\"client_ids\":[\"minagri-kubaka-web\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Nothing was changed")));
        nothingChanged();
        assertThat(denied("minagri-kubaka-web")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"minisante-hmis", "gov-health", "sso-admin-ui"})
    void revokingOnAClientOutsideMyScopeIs403(String clientId) throws Exception {
        mvc.perform(post("/institution-admin/access/revoke").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_ids\":[\"u-a1\"],\"client_ids\":[\"" + clientId + "\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("client_out_of_scope"));
        nothingChanged();
    }

    @Test
    void aSharedClientRevokesOnlyMyUsersAndNeverTheGroups() throws Exception {
        mvc.perform(post("/institution-admin/access/revoke").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_ids\":[\"u-a1\",\"u-a2\"],\"client_ids\":[\"gov-portal\"]}"))
                .andExpect(status().isOk());
        assertThat(denied("gov-portal")).containsExactlyInAnyOrder("u-a1", "u-a2");
        assertThat(denied("gov-portal")).doesNotContain("u-b1");
        assertThat(assignedGroups("gov-portal")).containsExactly("g-minagri", "g-minisante");
        // denied, yet still a member of the institution group: the deny beats the allow
        assertThat(memberships.get("u-a1")).containsExactly("g-minagri");
        mvc.perform(get("/institution-admin/apps/gov-portal/users").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/institution-admin/apps/gov-portal/users").with(TestCallers.institutionAdmin(B, "admin")))
                .andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.contains("u-b1")));
    }

    @Test
    void clientsForUsersWithAnotherInstitutionsUserIs403NotDropped() throws Exception {
        mvc.perform(post("/institution-admin/access/clients-for-users").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"user_ids\":[\"u-a1\",\"u-b1\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void allClientsConsidersOnlyMyManageableClients() throws Exception {
        mvc.perform(post("/institution-admin/access/revoke").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_ids\":[\"u-a1\"],\"all_clients\":true,\"confirm_all_clients\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clients_considered").value(
                        org.hamcrest.Matchers.containsInAnyOrder("gov-portal", "minagri-kubaka-web")));
        assertThat(denied("minisante-hmis")).isEmpty();
        assertThat(denied("gov-health")).isEmpty();
    }
}
