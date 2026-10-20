package rw.gov.risa.sso.wrapper.isolation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminMvcSupport;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/**
 * SPEC §7.1 — the per-object half of the mandatory institution filter: which group's members count as
 * "mine" (the template's {@code members.group}, departments when {@code include_subgroups}).
 */
class MembershipFilterIsolationTest extends InstitutionAdminMvcSupport {

    @Test
    void aMemberOfMyInstitutionPasses() throws Exception {
        mvc.perform(get("/institution-admin/users/u-a1").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk());
    }

    @Test
    void aMemberOfAnotherInstitutionIsRefused() throws Exception {
        mvc.perform(get("/institution-admin/users/u-b1").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("not_institution_member"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not a member of your institution")));
        verify(kc, never()).getUser(any(), anyString());
    }

    @Test
    void aUserInNoGroupIsRefused() throws Exception {
        user("u-orphan", "orphan", A, "none");
        memberships.put("u-orphan", new java.util.ArrayList<>());
        mvc.perform(get("/institution-admin/users/u-orphan").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPrefixCollisionIsNotMyInstitution() throws Exception {
        group("g-lookalike", "minagri-legacy", "/minagri-legacy");
        user("u-look", "lookalike", "minagri-legacy", "g-lookalike");
        mvc.perform(get("/institution-admin/users/u-look").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
    }

    @Test
    void departmentsAreInScopeWhenTheTemplateSaysSo() throws Exception {
        mvc.perform(get("/institution-admin/users/u-a3").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk());
    }

    @Test
    void anotherInstitutionsDepartmentIsStillOutOfScope() throws Exception {
        group("g-minisante-clinical", "clinical", "/minisante/clinical");
        user("u-b2", "beata", B, "g-minisante-clinical");
        mvc.perform(get("/institution-admin/users/u-b2").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnonboardedInstitutionIs404NotASilentEmptyScope() throws Exception {
        groups.remove("g-minagri");
        groups.remove("g-minagri-planning");
        mvc.perform(get("/institution-admin/users").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Institution group '/minagri' not found."));
        verify(kc, never()).groupMembers(any(), anyString());
    }

    @Test
    void theGroupComesFromTheTemplate() throws Exception {
        mvc.perform(get("/institution-admin/users").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk());
        verify(kc).groupByPath(any(), org.mockito.ArgumentMatchers.eq("/minagri"));
        verify(kc, never()).groupMembers(any(), org.mockito.ArgumentMatchers.eq("g-minisante"));
    }

    @Test
    void anUnknownUserIs404() throws Exception {
        mvc.perform(get("/institution-admin/users/u-ghost").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isNotFound());
        List.of();
    }

    @Test
    void aDepartmentKeycloakRefusesTheCallerOnIsSkippedNotFatal() throws Exception {
        // created after the FGAP plan was applied: Keycloak returns it with access.view=false for the caller
        group("g-minagri-new", "newdept", "/minagri/newdept");
        groups.get("g-minagri-new").setAccess(Map.of("view", false));
        user("u-a9", "newbie", A, "g-minagri-new");
        mvc.perform(get("/institution-admin/users").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.containsInAnyOrder("u-a1", "u-a2", "u-a3")));
        verify(kc, never()).groupChildren(any(), org.mockito.ArgumentMatchers.eq("g-minagri-new"));
        verify(kc, never()).groupMembers(any(), org.mockito.ArgumentMatchers.eq("g-minagri-new"));
    }

    @Test
    void aForbiddenDepartmentWalkIsSkippedButAForbiddenRootIsNot() throws Exception {
        org.mockito.Mockito.when(kc.groupChildren(any(), org.mockito.ArgumentMatchers.eq("g-minagri-planning")))
                .thenThrow(new jakarta.ws.rs.ForbiddenException());
        mvc.perform(get("/institution-admin/users").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.containsInAnyOrder("u-a1", "u-a2")));
        org.mockito.Mockito.when(kc.groupChildren(any(), org.mockito.ArgumentMatchers.eq("g-minagri")))
                .thenThrow(new jakarta.ws.rs.ForbiddenException());
        mvc.perform(get("/institution-admin/users").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
    }
}
