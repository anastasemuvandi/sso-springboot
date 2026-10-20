package rw.gov.risa.sso.wrapper.service.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import rw.gov.risa.sso.wrapper.keycloak.KnownClientsCache;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessReason;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews.LoginCheck;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/**
 * Spec §5.1 / invariant §7.8: login, token and denial events are ACTUALLY written. Here the audit service
 * is a WireMock server reached through the REAL {@code HttpAuditEmitter} (no audit mock anywhere), and the
 * access gate is the real {@code ClientAccessService.enforceLogin} (only its Keycloak read is stubbed).
 */
@WrapperTest
@Import(GatewayAuditTrailTest.Codes.class)
class GatewayAuditTrailTest {

    static final WireMockServer KC = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static final WireMockServer AUDIT = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static final String OIDC = "/realms/" + GatewayTestKeys.REALM + "/protocol/openid-connect";
    static final String APP = "minagri-kubaka-web";
    static final String SECRET = "s3cret";

    static {
        KC.start();
        AUDIT.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("sso.keycloak.server-url", KC::baseUrl);
        r.add("sso.keycloak.local-issuer", KC::baseUrl);
        r.add("sso.audit.service-url", AUDIT::baseUrl);
        r.add("sso.audit.api-key", () -> "audit-key-1");
    }

    @TestConfiguration
    static class Codes {
        @Bean
        @Primary
        InMemoryAuthCodeStore auditTrailCodes() {
            return new InMemoryAuthCodeStore();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired InMemoryAuthCodeStore codes;
    @MockitoSpyBean ClientAccessService access;
    @MockitoBean JoinKeyService joinKeys;
    @MockitoBean MfaService mfa;
    @MockitoBean GatewayDirectory directory;
    @MockitoBean KnownClientsCache knownClients;

    @BeforeEach
    void setUp() throws Exception {
        KC.resetAll();
        AUDIT.resetAll();
        AUDIT.stubFor(WireMock.post(urlEqualTo("/audit/events")).willReturn(aResponse().withStatus(201)));
        KC.stubFor(WireMock.get(urlEqualTo(OIDC + "/certs")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(GatewayTestKeys.jwks())));
        KC.stubFor(WireMock.post(urlEqualTo(OIDC + "/logout")).willReturn(aResponse().withStatus(204)));
        KC.stubFor(WireMock.post(urlEqualTo(OIDC + "/token")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody(new ObjectMapper().writeValueAsString(Map.of("access_token", token(), "refresh_token", "r2",
                        "id_token", "i", "token_type", "Bearer", "expires_in", 300)))));
        when(knownClients.isKnown(anyString())).thenReturn(true);
        RegisteredClient app = new RegisteredClient("u", APP, "Kubaka", true, false, false,
                List.of("https://kubaka.minagri.gov.rw/callback"), List.of(), null, null);
        when(directory.findClient(APP)).thenReturn(Optional.of(app));
        when(directory.secretMatches(app, SECRET)).thenReturn(true);
        allow(true);
    }

    static String token() {
        return GatewayTestKeys.token(KC.baseUrl()).user("jdoe").client(APP).sign();
    }

    void allow(boolean yes) {
        LoginCheck check = yes
                ? new LoginCheck(true, AccessReason.ASSIGNED, false, List.of("minagri"), List.of(), "Access granted.")
                : new LoginCheck(false, AccessReason.NOT_ASSIGNED, false, List.of(), List.of(),
                "Access denied. User 'jdoe' is not assigned to client '" + APP + "'.");
        doReturn(check).when(access).checkLogin(eq("jdoe"), eq(APP));
    }

    @Test
    void loginSuccessIsWritten() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"username\":\"jdoe\",\"password\":\"pw\",\"client_id\":\"" + APP + "\",\"client_secret\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk());
        awaitEvent(postRequestedFor(urlEqualTo("/audit/events"))
                .withHeader("X-Audit-API-Key", equalTo("audit-key-1"))
                .withRequestBody(matchingJsonPath("$.event_type", equalTo("auth.login.success")))
                .withRequestBody(matchingJsonPath("$.performed_by", equalTo("jdoe")))
                .withRequestBody(matchingJsonPath("$.app_id", equalTo(APP))));
    }

    @Test
    void loginFailureIsWritten() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"username\":\"jdoe\",\"password\":\"pw\",\"client_id\":\"" + APP + "\"}"))
                .andExpect(status().isUnauthorized());
        awaitEvent(postRequestedFor(urlEqualTo("/audit/events"))
                .withRequestBody(matchingJsonPath("$.event_type", equalTo("auth.gateway.login_failed"))));
    }

    @Test
    void tokenIssueAndRefreshAreWritten() throws Exception {
        String code = codes.issue(new AuthCodeStore.StoredCode(Map.of("access_token", token(), "refresh_token", "r",
                "id_token", "i", "token_type", "bearer"), APP, "https://kubaka.minagri.gov.rw/callback",
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", "S256", "jdoe"));
        mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + java.util.Base64.getEncoder()
                                .encodeToString((APP + ":" + SECRET).getBytes()))
                        .param("grant_type", "authorization_code").param("code", code)
                        .param("redirect_uri", "https://kubaka.minagri.gov.rw/callback")
                        .param("code_verifier", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .andExpect(status().isOk());
        awaitEvent(postRequestedFor(urlEqualTo("/audit/events"))
                .withRequestBody(matchingJsonPath("$.event_type", equalTo("authz.token.validated")))
                .withRequestBody(matchingJsonPath("$.details.grant_type", equalTo("authorization_code"))));

        mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token").param("client_id", APP).param("client_secret", SECRET)
                        .param("refresh_token", "r1"))
                .andExpect(status().isOk());
        awaitEvent(postRequestedFor(urlEqualTo("/audit/events"))
                .withRequestBody(matchingJsonPath("$.event_type", equalTo("session.refreshed"))));
    }

    @Test
    void deniedRefreshIsWrittenByTheGateAndTheDoor() throws Exception {
        allow(false);
        mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token").param("client_id", APP).param("client_secret", SECRET)
                        .param("refresh_token", "r1"))
                .andExpect(status().isForbidden());
        awaitEvent(postRequestedFor(urlEqualTo("/audit/events"))
                .withRequestBody(matchingJsonPath("$.event_type", equalTo("authz.access.denied")))
                .withRequestBody(matchingJsonPath("$.performed_by", equalTo("jdoe")))
                .withRequestBody(matchingJsonPath("$.details.grant_type", equalTo("refresh_token"))));
        awaitEvent(postRequestedFor(urlEqualTo("/audit/events"))
                .withRequestBody(matchingJsonPath("$.event_type", equalTo("authz.token.invalid"))));
    }

    @Test
    void deniedLoginIsWritten() throws Exception {
        allow(false);
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"username\":\"jdoe\",\"password\":\"pw\",\"client_id\":\"" + APP + "\",\"client_secret\":\"" + SECRET + "\"}"))
                .andExpect(status().isForbidden());
        awaitEvent(postRequestedFor(urlEqualTo("/audit/events"))
                .withRequestBody(matchingJsonPath("$.event_type", equalTo("authz.access.denied")))
                .withRequestBody(matchingJsonPath("$.details.grant_type", equalTo("password"))));
    }

    /** The emitter posts off-thread: wait (bounded) for the request to reach the audit service. */
    static void awaitEvent(RequestPatternBuilder pattern) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (!AUDIT.findAll(pattern).isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(AUDIT.findAll(pattern)).as("audit event never reached the audit service; got "
                + AUDIT.getAllServeEvents().stream().map(e -> e.getRequest().getBodyAsString()).toList()).isNotEmpty();
    }
}
