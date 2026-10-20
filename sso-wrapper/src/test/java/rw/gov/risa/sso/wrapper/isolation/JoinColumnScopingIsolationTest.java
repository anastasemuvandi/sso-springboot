package rw.gov.risa.sso.wrapper.isolation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminMvcSupport;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumnSetting;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/** SPEC §7.1 — the join column of another institution's (or a shared) client is not one institution's to set. */
class JoinColumnScopingIsolationTest extends InstitutionAdminMvcSupport {

    @ParameterizedTest
    @ValueSource(strings = {"minisante-hmis", "sso-admin-ui", "gov-health"})
    void settingOrReadingAnotherInstitutionsJoinColumnIs403(String clientId) throws Exception {
        mvc.perform(put("/institution-admin/apps/" + clientId + "/join-column").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"join_column\":\"nid\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/institution-admin/apps/" + clientId + "/join-column").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
        verify(joinKeys, never()).setJoinColumn(anyString(), any(), anyString(), anyBoolean());
    }

    @Test
    void aCrossCuttingClientCannotBeReKeyedByOneInstitutionButIsReadable() throws Exception {
        when(joinKeys.getSetting(anyString())).thenAnswer(i -> JoinColumnSetting.unset(i.getArgument(0)));
        mvc.perform(put("/institution-admin/apps/gov-portal/join-column").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"join_column\":\"nid\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("platform admin")));
        mvc.perform(get("/institution-admin/apps/gov-portal/join-column").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk());
        verify(joinKeys, never()).setJoinColumn(anyString(), any(), anyString(), anyBoolean());
    }

    @Test
    void aTierWithoutManageClientsCannotSetAJoinColumn() throws Exception {
        mvc.perform(put("/institution-admin/apps/minagri-kubaka-web/join-column").with(TestCallers.institutionAdmin(A, "auditor"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"join_column\":\"nid\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("capability_denied"));
    }

    @Test
    void theWorklistIsAlwaysTheCallersInstitution() throws Exception {
        mvc.perform(get("/institution-admin/apps/join-columns").param("institution", B)
                        .with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
        verify(joinKeys, never()).backfillWorklist(any());
    }
}
