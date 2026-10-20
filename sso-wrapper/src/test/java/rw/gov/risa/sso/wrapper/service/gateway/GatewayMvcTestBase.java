package rw.gov.risa.sso.wrapper.service.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.keycloak.KnownClientsCache;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/**
 * Shared MockMvc fixture for the gateway: WireMock stands in for Keycloak's OIDC endpoints
 * ({@code /token}, {@code /certs}, {@code /logout}, {@code /token/introspect}), tokens are RS256-signed
 * with {@link GatewayTestKeys} and verified by the REAL core validator. The three gate services, the
 * Admin-API directory and the audit emitter are mocks; codes live in {@link InMemoryAuthCodeStore}.
 *
 * <p>Clients: {@value #APP} (confidential, secret {@value #APP_SECRET}, redirect {@value #REDIRECT}),
 * {@value #SPA} (public, same redirect).
 */
@WrapperTest
@Import(GatewayMvcTestBase.Codes.class)
public abstract class GatewayMvcTestBase {

    public static final String APP = "minagri-kubaka-web";
    public static final String APP_SECRET = "s3cret-app";
    public static final String SPA = "minagri-spa";
    public static final String REDIRECT = "https://kubaka.minagri.gov.rw/callback";
    public static final String USER = "jdoe";
    public static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    public static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    public static final String OIDC = "/realms/" + GatewayTestKeys.REALM + "/protocol/openid-connect";

    protected static final WireMockServer KC = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    protected static final ObjectMapper JSON = new ObjectMapper();

    static {
        KC.start();
    }

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry r) {
        r.add("sso.keycloak.server-url", KC::baseUrl);
        r.add("sso.keycloak.public-url", () -> "https://login.gov.rw");
        r.add("sso.keycloak.local-issuer", KC::baseUrl);
        r.add("sso.keycloak.realm", () -> GatewayTestKeys.REALM);
        r.add("sso.api-base-url", () -> "https://sso.gov.rw");
    }

    @TestConfiguration
    static class Codes {
        @Bean
        @Primary
        InMemoryAuthCodeStore inMemoryAuthCodeStore() {
            return new InMemoryAuthCodeStore();
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected InMemoryAuthCodeStore codes;

    @MockitoBean protected AuditEmitter audit;
    @MockitoBean protected KnownClientsCache knownClients;
    @MockitoBean protected ClientAccessService access;
    @MockitoBean protected JoinKeyService joinKeys;
    @MockitoBean protected MfaService mfa;
    @MockitoBean protected GatewayDirectory directory;

    protected RegisteredClient app;
    protected RegisteredClient spa;

    @BeforeEach
    void baseFixture() {
        KC.resetAll();
        KC.stubFor(get(urlEqualTo(OIDC + "/certs")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody(GatewayTestKeys.jwks())));
        KC.stubFor(post(urlEqualTo(OIDC + "/logout")).willReturn(aResponse().withStatus(204)));
        codes.clear();
        // Builders reach emit(AuditEvent) so tests verify the built event.
        doCallRealMethod().when(audit).emit(any(AuditEvent.Builder.class));
        when(knownClients.isKnown(anyString())).thenReturn(true);
        app = new RegisteredClient("uuid-app", APP, "Kubaka", true, false, false, List.of(REDIRECT),
                List.of("https://kubaka.minagri.gov.rw/bye"), "https://kubaka.minagri.gov.rw", "/");
        spa = new RegisteredClient("uuid-spa", SPA, "SPA", true, true, false, List.of(REDIRECT), List.of(), null, null);
        when(directory.findClient(anyString())).thenReturn(Optional.empty());
        when(directory.findClient(APP)).thenReturn(Optional.of(app));
        when(directory.findClient(SPA)).thenReturn(Optional.of(spa));
        when(directory.secretMatches(any(), anyString())).thenReturn(false);
        when(directory.secretMatches(any(), eq(APP_SECRET))).thenReturn(true);
    }

    // ---- helpers ---------------------------------------------------------------------------

    protected static GatewayTestKeys.Token token() {
        return GatewayTestKeys.token(KC.baseUrl()).user(USER).client(APP);
    }

    protected static String userToken() {
        return token().sign();
    }

    /** Keycloak answers the token endpoint with these tokens (for any form). */
    protected static void keycloakIssues(String accessToken) {
        KC.stubFor(post(urlEqualTo(OIDC + "/token")).willReturn(json(200, Map.of(
                "access_token", accessToken, "refresh_token", "kc-refresh-2", "id_token", "kc-id",
                "token_type", "Bearer", "expires_in", 300))));
    }

    /** Keycloak refuses grants carrying {@code field=value}. */
    protected static void keycloakRefuses(String field, String value, int status, String error, String description) {
        KC.stubFor(post(urlEqualTo(OIDC + "/token")).withFormParam(field, equalTo(value)).atPriority(1)
                .willReturn(json(status, Map.of("error", error, "error_description", description))));
    }

    protected static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, Object body) {
        try {
            return aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                    .withBody(JSON.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static int logoutCalls() {
        return KC.findAll(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathEqualTo(OIDC + "/logout")))
                .size();
    }

    protected static String basic(String id, String secret) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString((id + ":" + secret).getBytes());
    }

    /** Put a wrapper code straight into the store (as authorize-internal would after its gates). */
    protected String wrapperCode(String accessToken) {
        return codes.issue(new AuthCodeStore.StoredCode(Map.of("access_token", accessToken,
                "refresh_token", "pw-refresh", "id_token", "pw-id", "token_type", "bearer"), APP, REDIRECT, CHALLENGE,
                Pkce.S256, USER));
    }
}
