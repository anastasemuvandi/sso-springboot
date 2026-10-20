package rw.gov.risa.sso.wrapper.api.audit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.service.platformops.audit.AuditQuery;
import rw.gov.risa.sso.wrapper.service.platformops.audit.AuditServiceClient;
import rw.gov.risa.sso.wrapper.support.TestCallers;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/**
 * {@code /audit/*} against WireMock standing in for the audit-service: the API key is injected server-side,
 * upstream failures are SURFACED (status + the audit-service's own envelope), exports stream with their
 * content-disposition, and the institution-scoped read always pins the institution.
 */
@WrapperTest
class AuditProxyControllerTest {

    static final WireMockServer AUDIT = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        AUDIT.start();
    }

    @DynamicPropertySource
    static void auditService(DynamicPropertyRegistry r) {
        r.add("sso.audit.service-url", AUDIT::baseUrl);
        r.add("sso.audit.api-key", () -> "test-audit-key");
    }

    @AfterAll
    static void stop() {
        AUDIT.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired AuditServiceClient client;
    @MockitoBean AuditEmitter audit;
    @MockitoBean KeycloakAdminFactory factory;

    @BeforeEach
    void reset() {
        AUDIT.resetAll();
    }

    @Test
    void eventsArePassedThroughWithTheKeyAddedServerSide() throws Exception {
        AUDIT.stubFor(get(urlPathEqualTo("/audit/events")).withHeader("X-Audit-API-Key", equalTo("test-audit-key"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"total\":1,\"limit\":50,\"offset\":0,\"items\":[{\"id\":7,\"event_type\":"
                                + "\"auth.login.success\",\"institution\":\"minagri\"}]}")));
        mvc.perform(MockMvcRequestBuilders.get("/audit/events").param("institution", "minagri")
                        .param("limit", "50").param("from_date", "2026-10-01T00:00:00+02:00")
                        .with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].event_type").value("auth.login.success"));
        AUDIT.verify(getRequestedFor(urlPathEqualTo("/audit/events"))
                .withQueryParam("institution", equalTo("minagri"))
                .withQueryParam("limit", equalTo("50"))
                .withQueryParam("from_date", equalTo("2026-10-01T00:00:00+02:00"))
                .withHeader("X-Audit-API-Key", equalTo("test-audit-key")));
    }

    @Test
    void institutionAdminIsRefusedBeforeAnyUpstreamCall() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/audit/events").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/audit/export/csv").with(TestCallers.plainUser()))
                .andExpect(status().isForbidden());
        assertThat(AUDIT.getAllServeEvents()).isEmpty();
    }

    @Test
    void anUpstream5xxIsA502NotAnEmptyTable() throws Exception {
        AUDIT.stubFor(get(urlPathEqualTo("/audit/events")).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"internal_error\",\"message\":\"database is down\",\"details\":{}}")));
        mvc.perform(MockMvcRequestBuilders.get("/audit/events").with(TestCallers.superAdmin()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("audit_service_error"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("database is down")))
                .andExpect(jsonPath("$.details.upstream_status").value(500));
    }

    @Test
    void aRejectedApiKeyIsNeverRelayedAsA401() throws Exception {
        AUDIT.stubFor(get(urlPathEqualTo("/audit/retention/policies")).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"unauthorized\",\"message\":\"Invalid or missing API key\",\"details\":{}}")));
        mvc.perform(MockMvcRequestBuilders.get("/audit/retention/policies").with(TestCallers.superAdmin()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("audit_service_error"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("AUDIT_API_KEY")));
    }

    @Test
    void anUpstreamValidationErrorKeepsItsStatusAndEnvelope() throws Exception {
        AUDIT.stubFor(get(urlPathEqualTo("/audit/integrity/verify")).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"validation_error\",\"message\":\"Invalid date\","
                        + "\"details\":{\"fields\":{\"from_date\":\"not a date\"}}}")));
        mvc.perform(MockMvcRequestBuilders.get("/audit/integrity/verify").param("from_date", "yesterday")
                        .with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.details.fields.from_date").value("not a date"))
                .andExpect(jsonPath("$.details.upstream").value("audit-service"));
    }

    @Test
    void integrityAndRetentionArePassedThrough() throws Exception {
        AUDIT.stubFor(get(urlPathEqualTo("/audit/integrity/verify")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"valid\":true,\"total_checked\":12,\"tampered_count\":0}")));
        AUDIT.stubFor(get(urlPathEqualTo("/audit/retention/policies")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"category\":null,\"retention_days\":2555,\"legal_hold\":false}]")));
        mvc.perform(MockMvcRequestBuilders.get("/audit/integrity/verify").with(TestCallers.admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        mvc.perform(MockMvcRequestBuilders.get("/audit/retention/policies").with(TestCallers.admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].retention_days").value(2555));
    }

    @Test
    void anUnreachableAuditServiceIs503() {
        AuditServiceClient dead = new AuditServiceClient("http://127.0.0.1:1", "k", Duration.ofSeconds(2),
                Duration.ofSeconds(2));
        assertThatThrownBy(() -> dead.listEvents(AuditQuery.empty()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(503);
                    assertThat(e.error()).isEqualTo("upstream_unavailable");
                });
    }

    @Test
    void csvExportStreamsTheBytesAndKeepsTheContentDisposition() throws Exception {
        AUDIT.stubFor(get(urlPathEqualTo("/audit/export/csv")).willReturn(aResponse()
                .withHeader("Content-Type", "text/csv; charset=utf-8")
                .withHeader("Content-Disposition", "attachment; filename=\"audit_export_20261020_101500.csv\"")
                .withBody("id,event_type\n1,auth.login.success\n")));
        MvcResult started = mvc.perform(MockMvcRequestBuilders.get("/audit/export/csv").param("event_type", "auth.login.success")
                        .with(TestCallers.superAdmin()))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"audit_export_20261020_101500.csv\""))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(content().string("id,event_type\n1,auth.login.success\n"));
        AUDIT.verify(getRequestedFor(urlPathEqualTo("/audit/export/csv"))
                .withQueryParam("event_type", equalTo("auth.login.success")));
    }

    @Test
    void pdfExportStreamsBinary() throws Exception {
        byte[] pdf = {'%', 'P', 'D', 'F', '-', '1', '.', '4', 0, (byte) 0xFF};
        AUDIT.stubFor(get(urlPathEqualTo("/audit/export/pdf")).willReturn(aResponse()
                .withHeader("Content-Type", "application/pdf")
                .withHeader("Content-Disposition", "attachment; filename=\"audit.pdf\"").withBody(pdf)));
        MvcResult started = mvc.perform(MockMvcRequestBuilders.get("/audit/export/pdf").with(TestCallers.superAdmin()))
                .andReturn();
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andExpect(content().bytes(pdf));
    }

    @Test
    void aFailedExportIsSurfacedNotStreamed() throws Exception {
        AUDIT.stubFor(get(urlPathEqualTo("/audit/export/pdf")).willReturn(aResponse().withStatus(503)
                .withBody("service unavailable")));
        mvc.perform(MockMvcRequestBuilders.get("/audit/export/pdf").with(TestCallers.superAdmin()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("audit_service_error"));
    }

    @Test
    void anUnknownExportFormatIs404AndAnOutOfRangeLimitIs422() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/audit/export/xlsx").with(TestCallers.superAdmin()))
                .andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.get("/audit/events").param("limit", "5000").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());
        assertThat(AUDIT.getAllServeEvents()).isEmpty();
    }

    @Test
    void theInstitutionScopedReadAlwaysPinsTheInstitution() {
        AUDIT.stubFor(get(urlPathEqualTo("/audit/events")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"total\":0,\"items\":[]}")));
        AuditQuery widened = new AuditQuery(null, null, null, null, null, "minisante", null, null, null, null, 10, 0);
        client.listEventsForInstitution("MINAGRI", widened);
        AUDIT.verify(getRequestedFor(urlPathEqualTo("/audit/events")).withQueryParam("institution", equalTo("minagri")));
        assertThatThrownBy(() -> client.listEventsForInstitution(" ", AuditQuery.empty()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(403));
    }
}
