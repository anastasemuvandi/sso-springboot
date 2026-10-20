package rw.gov.risa.sso.wrapper.isolation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminMvcSupport;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/** SPEC §7.1 — an admin of A cannot READ the users of B, whatever they know or send. */
class CannotReadAcrossInstitutionsIsolationTest extends InstitutionAdminMvcSupport {

    @Test
    void readingMyOwnUserWorks() throws Exception {
        mvc.perform(get("/institution-admin/users/u-a2").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("andre"));
    }

    @Test
    void readingAnotherInstitutionsUserIs403BeforeTheUserIsFetched() throws Exception {
        mvc.perform(get("/institution-admin/users/u-b1").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
        verify(kc, never()).getUser(any(), eq("u-b1"));
    }

    @Test
    void aReadOnlyTierStillCannotCross() throws Exception {
        mvc.perform(get("/institution-admin/users/u-b1").with(TestCallers.institutionAdmin(A, "auditor")))
                .andExpect(status().isForbidden());
    }

    @Test
    void namingTheOtherInstitutionIsRefusedNotHonoured() throws Exception {
        mvc.perform(get("/institution-admin/users/u-b1").param("institution", B)
                        .with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("institution_not_administered"));
        mvc.perform(get("/institution-admin/users").param("institution", B)
                        .with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theSymmetricCaseHoldsForB() throws Exception {
        mvc.perform(get("/institution-admin/users/u-a1").with(TestCallers.institutionAdmin(B, "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/institution-admin/users/u-b1").with(TestCallers.institutionAdmin(B, "admin")))
                .andExpect(status().isOk());
    }

    @Test
    void aPlatformAdminActingForAStillCannotReachBThroughThisLayer() throws Exception {
        mvc.perform(get("/institution-admin/users/u-b1").param("institution", A).with(TestCallers.superAdmin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherInstitutionsAuditTrailIsNotReachable() throws Exception {
        mvc.perform(get("/institution-admin/audit").param("institution", B).with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/institution-admin/audit").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk());
        verify(auditClient).listEventsForInstitution(eq(A), any());
        verify(auditClient, never()).listEventsForInstitution(eq(B), any());
    }

    @Test
    void anotherInstitutionsOverviewIsNotReachableAndMineCountsOnlyMine() throws Exception {
        mvc.perform(get("/institution-admin/overview").param("institution", B).with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/institution-admin/overview").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members").value(3))
                .andExpect(jsonPath("$.access.items[*].client_id",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("minisante-hmis"))));
    }
}
