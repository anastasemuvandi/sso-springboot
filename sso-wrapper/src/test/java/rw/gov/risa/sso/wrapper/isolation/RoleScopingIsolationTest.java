package rw.gov.risa.sso.wrapper.isolation;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
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

/** SPEC §7.1 — an admin may assign only roles of their own {@code inst:<slug>:*} namespace. */
class RoleScopingIsolationTest extends InstitutionAdminMvcSupport {

    @Test
    void assignableRolesExcludeEveryOtherInstitution() throws Exception {
        mvc.perform(get("/institution-admin/roles").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", everyItem(startsWith("inst:minagri:"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"inst:minisante:staff", "inst:minisante:admin", "inst:minisante:hr"})
    void assigningAnotherInstitutionsRoleIs403(String role) throws Exception {
        mvc.perform(post("/institution-admin/users/u-a1/roles").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("role_not_assignable"));
        verify(kc, never()).addRealmRoles(any(), anyString(), any());
    }

    @Test
    void revokingAnotherInstitutionsRoleIs403() throws Exception {
        mvc.perform(delete("/institution-admin/users/u-a1/roles").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"inst:minisante:staff\"}"))
                .andExpect(status().isForbidden());
        verify(kc, never()).removeRealmRoles(any(), anyString(), any());
    }

    @Test
    void aPrefixCollisionRoleIsNotMine() throws Exception {
        role("inst:minagri-legacy:staff");
        mvc.perform(post("/institution-admin/users/u-a1/roles").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"inst:minagri-legacy:staff\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTierWithoutMapRolesCanAssignNothing() throws Exception {
        mvc.perform(post("/institution-admin/users/u-a1/roles").with(TestCallers.institutionAdmin(A, "auditor"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"inst:minagri:staff\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("capability_denied"));
    }

    @Test
    void theHrTierMayGrantStaffOnly() throws Exception {
        mvc.perform(post("/institution-admin/users/u-a1/roles").with(TestCallers.institutionAdmin(A, "hr"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"inst:minagri:staff\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/institution-admin/users/u-a1/roles").with(TestCallers.institutionAdmin(A, "hr"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"inst:minagri:hr\"}"))
                .andExpect(status().isForbidden());
    }
}
