package rw.gov.risa.sso.wrapper.isolation;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminMvcSupport;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/** SPEC §7.1 — list and search return only the caller's institution, departments included, once each. */
class ListAndSearchAreScopedIsolationTest extends InstitutionAdminMvcSupport {

    @Test
    void listReturnsOnlyMyInstitutionsMembers() throws Exception {
        mvc.perform(get("/institution-admin/users").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", containsInAnyOrder("u-a1", "u-a2", "u-a3")));
        verify(kc, never()).groupMembers(any(), eq("g-minisante"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bosco", "minisante", "u-b1", "@minisante.gov.rw"})
    void searchCannotSurfaceAnotherInstitutionsUser(String term) throws Exception {
        mvc.perform(get("/institution-admin/users").param("search", term).with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "*", "%", "a"})
    void noSearchTermWidensTheScope(String term) throws Exception {
        mvc.perform(get("/institution-admin/users").param("search", term).with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("u-b1"))));
    }

    @Test
    void bAdminSymmetricallySeesOnlyB() throws Exception {
        mvc.perform(get("/institution-admin/users").with(TestCallers.institutionAdmin(B, "auditor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", containsInAnyOrder("u-b1")));
    }

    @Test
    void aMemberOfBothGroupAndDepartmentAppearsOnce() throws Exception {
        memberships.get("u-a3").add("g-minagri");
        mvc.perform(get("/institution-admin/users").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder("u-a1", "u-a2", "u-a3")));
    }

    @Test
    void walkingDepartmentsDoesNotWalkIntoAnotherInstitution() throws Exception {
        group("g-minisante-clinical", "clinical", "/minisante/clinical");
        user("u-b2", "beata", B, "g-minisante-clinical");
        mvc.perform(get("/institution-admin/users").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder("u-a1", "u-a2", "u-a3")));
    }
}
