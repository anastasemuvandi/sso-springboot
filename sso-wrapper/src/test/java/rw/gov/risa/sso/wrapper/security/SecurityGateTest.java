package rw.gov.risa.sso.wrapper.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.support.TestCallers;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

@WrapperTest
class SecurityGateTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuditEmitter audit;

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/health/")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("healthy"));
    }

    @Test
    void platformRouteWithoutTokenIs401Envelope() throws Exception {
        mvc.perform(get("/client/clients"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void invalidBearerIs401() throws Exception {
        mvc.perform(get("/client/clients").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void institutionAdminIsRefusedOnPlatformRoutes() throws Exception {
        for (String path : new String[] {"/client/clients", "/users/", "/agency/", "/realm/mfa", "/dashboard/overview",
                "/audit/events", "/delegated-permissions", "/institutions", "/lifecycle/audit"}) {
            mvc.perform(get(path).with(TestCallers.institutionAdmin("minagri", "admin")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("forbidden"));
        }
    }

    @Test
    void everyAdminTierRolePassesTheGate() throws Exception {
        // 404 (no such route yet) or 200 — anything but 401/403 proves the gate let them through.
        for (var caller : new org.springframework.test.web.servlet.request.RequestPostProcessor[] {
                TestCallers.superAdmin(), TestCallers.admin(), TestCallers.systemAdmin()}) {
            mvc.perform(get("/admin/__gate_probe__").with(caller))
                    .andExpect(r -> {
                        int s = r.getResponse().getStatus();
                        if (s == 401 || s == 403) {
                            throw new AssertionError("admin tier refused: " + s);
                        }
                    });
        }
    }

    @Test
    void verifyFailsClosedWithoutToken() throws Exception {
        mvc.perform(get("/auth/verify")).andExpect(status().isUnauthorized());
    }

    @Test
    void correlationIdIsEchoed() throws Exception {
        mvc.perform(get("/health/").header("X-Correlation-Id", "abc-123"))
                .andExpect(header().string("X-Correlation-Id", "abc-123"));
    }
}
