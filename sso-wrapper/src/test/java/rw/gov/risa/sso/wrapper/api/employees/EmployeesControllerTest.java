package rw.gov.risa.sso.wrapper.api.employees;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.support.TestCallers;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/** Gates and the no-mirror state (no database, no Keycloak). */
@WrapperTest
class EmployeesControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuditEmitter audit;
    @MockitoBean KeycloakAdminFactory keycloak;

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/employees")).andExpect(status().isUnauthorized());
    }

    @Test
    void plainUserHasNoDirectoryAndIsToldSoBeforeAnyDatabaseDiagnostic() throws Exception {
        mvc.perform(get("/employees").with(TestCallers.plainUser())).andExpect(status().isForbidden());
    }

    @Test
    void withoutAMirrorTheDirectoryIs503NamingIt() throws Exception {
        mvc.perform(get("/employees").with(TestCallers.superAdmin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("identity_db_not_configured"));
        mvc.perform(get("/employees/EMP1").with(TestCallers.superAdmin()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void lifecycleRoutesAreAdminTier() throws Exception {
        mvc.perform(post("/offboarding/terminate").with(TestCallers.institutionAdmin("minagri", "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"user_id\":\"u\",\"reason\":\"r\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/institutions").with(TestCallers.plainUser())).andExpect(status().isForbidden());
    }

    @Test
    void webhookIsPublicButOffWithoutASecret() throws Exception {
        mvc.perform(post("/ippis/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("not_configured"));
    }
}
