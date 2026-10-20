package rw.gov.risa.sso.wrapper.isolation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminMvcSupport;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/** SPEC §7.1 — an admin of A cannot MODIFY the users of B; a refused write records nothing. */
class CannotModifyAcrossInstitutionsIsolationTest extends InstitutionAdminMvcSupport {

    @AfterEach
    void nothingWasWrittenToB() {
        verify(kc, never()).updateUser(any(), org.mockito.ArgumentMatchers.eq("u-b1"), any());
        verify(kc, never()).logout(any(), org.mockito.ArgumentMatchers.eq("u-b1"));
        verify(kc, never()).addRealmRoles(any(), org.mockito.ArgumentMatchers.eq("u-b1"), any());
        verify(kc, never()).removeRealmRoles(any(), org.mockito.ArgumentMatchers.eq("u-b1"), any());
    }

    @Test
    void updatingAnotherInstitutionsUserIs403() throws Exception {
        mvc.perform(patch("/institution-admin/users/u-b1").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        verify(audit, never()).emit(any(rw.gov.risa.sso.wrapper.audit.AuditEvent.class));
    }

    @Test
    void deactivatingAnotherInstitutionsUserIs403() throws Exception {
        mvc.perform(post("/institution-admin/users/u-b1/deactivate").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
    }

    @Test
    void assigningOrRevokingARoleOnAnotherInstitutionsUserIs403() throws Exception {
        mvc.perform(post("/institution-admin/users/u-b1/roles").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"inst:minagri:staff\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/institution-admin/users/u-b1/roles").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"inst:minisante:staff\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCreatedUserIsPinnedToMyInstitutionWhateverTheQueryNames() throws Exception {
        mvc.perform(post("/institution-admin/users").param("institution", B).with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sneaky\",\"email\":\"s@x.rw\",\"firstName\":\"S\",\"lastName\":\"X\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("institution_not_administered"));
        verify(kc, never()).createUser(any(), any());
        mvc.perform(post("/institution-admin/users").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"fine\",\"email\":\"f@x.rw\",\"firstName\":\"F\",\"lastName\":\"X\","
                                + "\"attributes\":{\"institution\":[\"minisante\"]}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attributes.institution[0]").value(A))
                .andExpect(jsonPath("$.agencies[0].path").value("/minagri"));
    }

    @Test
    void anUpdateCannotMoveMyUserIntoAnotherInstitution() throws Exception {
        mvc.perform(patch("/institution-admin/users/u-a1").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"national_id\":\"1199080012345678\",\"attributes\":{\"institution\":[\"minisante\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes.institution[0]").value(A));
    }

    @Test
    void aTierWithoutManageMembersCannotWriteAtAll() throws Exception {
        mvc.perform(patch("/institution-admin/users/u-a1").with(TestCallers.institutionAdmin(A, "auditor"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("capability_denied"));
        verify(kc, never()).updateUser(any(), anyString(), any());
    }
}
