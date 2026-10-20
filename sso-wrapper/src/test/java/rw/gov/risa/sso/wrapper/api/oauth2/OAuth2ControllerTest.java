package rw.gov.risa.sso.wrapper.api.oauth2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.Map;

import com.github.tomakehurst.wiremock.client.WireMock;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.service.gateway.GatewayMvcTestBase;
import rw.gov.risa.sso.wrapper.service.gateway.GatewayTestKeys;

/** {@code /oauth2/*} with Keycloak's OIDC endpoints on WireMock and the core JWT validator for real. */
class OAuth2ControllerTest extends GatewayMvcTestBase {

    static MockHttpServletRequestBuilder tokenForm() {
        return post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED);
    }

    static MockHttpServletRequestBuilder authorize() {
        return get("/oauth2/authorize").param("client_id", APP).param("redirect_uri", REDIRECT)
                .param("response_type", "code").param("code_challenge", CHALLENGE);
    }

    // ---- GET /oauth2/authorize -------------------------------------------------------------

    @Nested
    class Authorize {

        @Test
        void validRequestRedirectsToKeycloakPublicAuthEndpointForwardingEverything() throws Exception {
            MvcResult r = mvc.perform(authorize().param("code_challenge_method", "S256").param("state", "st+{1}")
                            .param("nonce", "n-1").param("acr_values", "2").param("prompt", "login")
                            .param("login_hint", "jdoe@minagri.gov.rw"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location",
                            startsWith("https://login.gov.rw/realms/government-internal/protocol/openid-connect/auth?")))
                    .andReturn();
            Map<String, String> q = UriComponentsBuilder.fromUri(URI.create(r.getResponse().getHeader("Location")))
                    .build().getQueryParams().toSingleValueMap();
            assertThat(decode(q.get("state"))).isEqualTo("st+{1}");
            assertThat(q).containsEntry("client_id", APP).containsEntry("code_challenge", CHALLENGE)
                    .containsEntry("code_challenge_method", "S256").containsEntry("nonce", "n-1")
                    .containsEntry("acr_values", "2").containsEntry("prompt", "login").containsKey("login_hint")
                    .containsEntry("response_type", "code");
            assertThat(decode(q.get("redirect_uri"))).isEqualTo(REDIRECT);
            assertThat(decode(q.get("scope"))).isEqualTo("openid profile email");
            verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.AUTHORIZE_START && "success".equals(e.outcome())));
        }

        @Test
        void plainPkceIsRefused() throws Exception {
            mvc.perform(authorize().param("code_challenge_method", "plain"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"))
                    .andExpect(jsonPath("$.error_description").value(containsString("S256")));
        }

        @Test
        void missingChallengeIsRefused() throws Exception {
            mvc.perform(get("/oauth2/authorize").param("client_id", APP).param("redirect_uri", REDIRECT)
                            .param("response_type", "code"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        }

        @Test
        void malformedChallengeIsRefused() throws Exception {
            mvc.perform(get("/oauth2/authorize").param("client_id", APP).param("redirect_uri", REDIRECT)
                            .param("response_type", "code").param("code_challenge", "short"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void implicitFlowIsRefused() throws Exception {
            mvc.perform(get("/oauth2/authorize").param("client_id", APP).param("redirect_uri", REDIRECT)
                            .param("response_type", "token").param("code_challenge", CHALLENGE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("unsupported_response_type"));
        }

        @Test
        void redirectMustMatchExactly() throws Exception {
            for (String near : new String[] {REDIRECT + "/", REDIRECT + "?x=1", "https://kubaka.minagri.gov.rw/callbacK",
                    "https://evil.example/callback", "https://kubaka.minagri.gov.rw/callback/../steal"}) {
                mvc.perform(get("/oauth2/authorize").param("client_id", APP).param("redirect_uri", near)
                                .param("response_type", "code").param("code_challenge", CHALLENGE))
                        .andExpect(status().isBadRequest())
                        .andExpect(header().doesNotExist("Location"))
                        .andExpect(jsonPath("$.error_description").value("redirect_uri is not registered for this client"));
            }
            verify(audit, org.mockito.Mockito.times(5))
                    .emit(argThat((AuditEvent e) -> e.type() == AuditEventType.AUTHORIZE_REDIRECT_DENIED));
        }

        @Test
        void unknownClientIsRefusedWithoutRedirect() throws Exception {
            mvc.perform(get("/oauth2/authorize").param("client_id", "nope").param("redirect_uri", REDIRECT)
                            .param("response_type", "code").param("code_challenge", CHALLENGE))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_client"))
                    .andExpect(header().doesNotExist("Location"));
        }
    }

    // ---- POST /oauth2/authorize-internal ---------------------------------------------------

    @Nested
    class AuthorizeInternal {

        String body(String method) {
            return """
                    {"client_id":"%s","redirect_uri":"%s","refresh_token":"pw-refresh","id_token":"pw-id",
                     "code_challenge":"%s","code_challenge_method":"%s"}""".formatted(APP, REDIRECT, CHALLENGE, method);
        }

        @Test
        void issuesASingleUseCodeAfterTheGates() throws Exception {
            String token = userToken();
            MvcResult r = mvc.perform(post("/oauth2/authorize-internal").contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).content(body("S256")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.authorization_code").isString()).andReturn();
            verify(access).enforceLogin(USER, APP, "127.0.0.1", "authorize_internal");
            verify(joinKeys).enforceLogin(USER, APP, "127.0.0.1", "authorize_internal");
            verify(mfa).enforcePasswordGrant(any(), eq(USER), eq(APP), any(), eq("/oauth2/authorize-internal"));
            verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.AUTHORIZE_INTERNAL_CODE
                    && "success".equals(e.outcome()) && USER.equals(e.actor())));
            String code = JSON.readTree(r.getResponse().getContentAsString()).get("authorization_code").asText();
            assertThat(codes.consume(code)).get().satisfies(c -> {
                assertThat(c.tokens()).containsEntry("access_token", token).containsEntry("refresh_token", "pw-refresh");
                assertThat(c.codeChallengeMethod()).isEqualTo("S256");
            });
        }

        @Test
        void withoutBearerIs401() throws Exception {
            mvc.perform(post("/oauth2/authorize-internal").contentType(MediaType.APPLICATION_JSON).content(body("S256")))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void plainPkceIsRefused() throws Exception {
            mvc.perform(post("/oauth2/authorize-internal").contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken()).content(body("plain")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        }

        @Test
        void tokenWithoutPreferredUsernameIs401AndNeverSkipsTheGates() throws Exception {
            String token = token().claim("preferred_username", null).sign();
            mvc.perform(post("/oauth2/authorize-internal").contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).content(body("S256")))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_token"));
            verify(access, never()).enforceLogin(any(), any(), any(), any());
        }

        @Test
        void forgedTokenIs401() throws Exception {
            String token = token().signedByAnotherKey().sign();
            mvc.perform(post("/oauth2/authorize-internal").contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).content(body("S256")))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void tokenOfAnotherClientCannotBecomeACodeForThisOne() throws Exception {
            String token = token().client(SPA).sign();
            mvc.perform(post("/oauth2/authorize-internal").contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).content(body("S256")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        }

        @Test
        void unregisteredRedirectIsRefused() throws Exception {
            mvc.perform(post("/oauth2/authorize-internal").contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken())
                            .content(body("S256").replace(REDIRECT, "https://evil.example/cb")))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---- POST /oauth2/token: client authentication -----------------------------------------

    @Nested
    class ClientAuthentication {

        @Test
        void confidentialClientWithoutSecretIsRefused() throws Exception {
            keycloakIssues(userToken());
            mvc.perform(tokenForm().param("grant_type", "refresh_token").param("client_id", APP)
                            .param("refresh_token", "r1"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_client"))
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Basic")));
            // never looked up nor forwarded on the caller's behalf
            KC.verify(0, postRequestedFor(urlEqualTo(OIDC + "/token")));
            verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.TOKEN_REJECTED));
        }

        @Test
        void wrongSecretIsRefused() throws Exception {
            mvc.perform(tokenForm().param("grant_type", "refresh_token").param("client_id", APP)
                            .param("client_secret", "guess").param("refresh_token", "r1"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_client"));
        }

        @Test
        void secretInTheFormIsAccepted() throws Exception {
            keycloakIssues(userToken());
            mvc.perform(tokenForm().param("grant_type", "refresh_token").param("client_id", APP)
                            .param("client_secret", APP_SECRET).param("refresh_token", "r1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.access_token").isString())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
            KC.verify(postRequestedFor(urlEqualTo(OIDC + "/token")).withFormParam("client_secret", equalTo(APP_SECRET)));
        }

        @Test
        void secretInHttpBasicIsAccepted() throws Exception {
            keycloakIssues(userToken());
            mvc.perform(tokenForm().header(HttpHeaders.AUTHORIZATION, basic(APP, APP_SECRET))
                            .param("grant_type", "refresh_token").param("refresh_token", "r1"))
                    .andExpect(status().isOk());
        }

        @Test
        void bothMethodsAtOnceIsRefused() throws Exception {
            mvc.perform(tokenForm().header(HttpHeaders.AUTHORIZATION, basic(APP, APP_SECRET))
                            .param("grant_type", "refresh_token").param("client_secret", APP_SECRET)
                            .param("refresh_token", "r1"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        }

        @Test
        void publicClientNeedsNoSecretButCannotUseClientCredentials() throws Exception {
            keycloakIssues(token().client(SPA).sign());
            mvc.perform(tokenForm().param("grant_type", "refresh_token").param("client_id", SPA)
                            .param("refresh_token", "r1"))
                    .andExpect(status().isOk());
            mvc.perform(tokenForm().param("grant_type", "client_credentials").param("client_id", SPA))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_client"));
        }

        @Test
        void unsupportedGrantType() throws Exception {
            mvc.perform(tokenForm().param("grant_type", "password").param("client_id", APP)
                            .param("client_secret", APP_SECRET))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("unsupported_grant_type"));
        }
    }

    // ---- POST /oauth2/token: authorization_code --------------------------------------------

    @Nested
    class AuthorizationCode {

        MockHttpServletRequestBuilder redeem(String code, String verifier) {
            return tokenForm().header(HttpHeaders.AUTHORIZATION, basic(APP, APP_SECRET))
                    .param("grant_type", "authorization_code").param("code", code)
                    .param("redirect_uri", REDIRECT).param("code_verifier", verifier);
        }

        @Test
        void wrapperCodeRedeemsOnceThroughTheGates() throws Exception {
            String token = userToken();
            String code = wrapperCode(token);
            mvc.perform(redeem(code, VERIFIER)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.access_token").value(token))
                    .andExpect(jsonPath("$.refresh_token").value("pw-refresh"))
                    .andExpect(jsonPath("$.token_type").value("bearer"))
                    .andExpect(jsonPath("$.expires_in").isNumber());
            verify(access).enforceLogin(USER, APP, "127.0.0.1", "authorization_code");
            verify(mfa).enforcePasswordGrant(any(), eq(USER), eq(APP), any(), eq("/oauth2/token"));
            verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.TOKEN_ISSUED && USER.equals(e.actor())));

            // Second redemption: the code is gone; Keycloak (which never issued it) refuses too.
            keycloakRefuses("code", code, 400, "invalid_grant", "Code not valid");
            mvc.perform(redeem(code, VERIFIER)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_grant"));
        }

        @Test
        void codeIsSpentEvenWhenTheFirstRedemptionFailed() throws Exception {
            String code = wrapperCode(userToken());
            String wrongVerifier = "x".repeat(43);
            mvc.perform(redeem(code, wrongVerifier)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_grant"))
                    .andExpect(jsonPath("$.error_description").value("PKCE verification failed"));
            keycloakRefuses("code", code, 400, "invalid_grant", "Code not valid");
            mvc.perform(redeem(code, VERIFIER)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_grant"));
            assertThat(codes.consume(code)).isEmpty();
            verify(access, never()).enforceLogin(any(), any(), any(), any());
        }

        @Test
        void codeIsSpentEvenWhenAGateRefusedIt() throws Exception {
            String code = wrapperCode(userToken());
            doThrow(ApiException.forbidden("access_denied", "Access denied."))
                    .when(access).enforceLogin(eq(USER), eq(APP), any(), any());
            mvc.perform(redeem(code, VERIFIER)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("access_denied"));
            assertThat(codes.consume(code)).isEmpty();
        }

        @Test
        void wrapperCodeBoundToRedirectUri() throws Exception {
            String code = wrapperCode(userToken());
            mvc.perform(tokenForm().header(HttpHeaders.AUTHORIZATION, basic(APP, APP_SECRET))
                            .param("grant_type", "authorization_code").param("code", code)
                            .param("redirect_uri", REDIRECT + "/other").param("code_verifier", VERIFIER))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        }

        @Test
        void missingVerifierIsRefused() throws Exception {
            mvc.perform(tokenForm().header(HttpHeaders.AUTHORIZATION, basic(APP, APP_SECRET))
                            .param("grant_type", "authorization_code").param("code", "c")
                            .param("redirect_uri", REDIRECT))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        }

        @Test
        void keycloakCodeIsExchangedThenGatedWithoutThePasswordMfaGate() throws Exception {
            keycloakIssues(token().claim("acr", "2").sign());
            mvc.perform(redeem("kc-code-1", VERIFIER)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.expires_in").value(300));
            KC.verify(postRequestedFor(urlEqualTo(OIDC + "/token")).withFormParam("code", equalTo("kc-code-1"))
                    .withFormParam("code_verifier", equalTo(VERIFIER)).withFormParam("redirect_uri", equalTo(REDIRECT)));
            verify(access).enforceLogin(USER, APP, "127.0.0.1", "authorization_code");
            verify(joinKeys).enforceLogin(USER, APP, "127.0.0.1", "authorization_code");
            verify(mfa, never()).enforcePasswordGrant(any(), any(), any(), any(), any());
        }

        @Test
        void keycloakCodeRefusedByAGateEndsTheMintedSession() throws Exception {
            keycloakIssues(userToken());
            doThrow(ApiException.forbidden("access_denied", "Access denied. User 'jdoe' is not assigned."))
                    .when(access).enforceLogin(eq(USER), eq(APP), any(), any());
            mvc.perform(redeem("kc-code-2", VERIFIER)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("access_denied"))
                    .andExpect(jsonPath("$.access_token").doesNotExist());
            assertThat(logoutCalls()).isEqualTo(1);
        }

        @Test
        void keycloakTokenWithoutPreferredUsernameIs401() throws Exception {
            keycloakIssues(token().claim("preferred_username", null).sign());
            mvc.perform(redeem("kc-code-3", VERIFIER)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("invalid_token"));
        }

        @Test
        void keycloakRefusalIsPassedThroughAsRfc6749() throws Exception {
            keycloakRefuses("code", "kc-bad", 400, "invalid_grant", "Code not valid");
            mvc.perform(redeem("kc-bad", VERIFIER)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_grant"))
                    .andExpect(jsonPath("$.error_description").value("Code not valid"));
        }
    }

    // ---- POST /oauth2/token: refresh_token -------------------------------------------------

    @Nested
    class Refresh {

        MockHttpServletRequestBuilder refresh() {
            return tokenForm().param("grant_type", "refresh_token").param("client_id", APP)
                    .param("client_secret", APP_SECRET).param("refresh_token", "kc-refresh-1");
        }

        @Test
        void refreshPassesAllThreeGates() throws Exception {
            keycloakIssues(userToken());
            mvc.perform(refresh()).andExpect(status().isOk()).andExpect(jsonPath("$.refresh_token").value("kc-refresh-2"));
            verify(access).enforceLogin(USER, APP, "127.0.0.1", "refresh_token");
            verify(joinKeys).enforceLogin(USER, APP, "127.0.0.1", "refresh_token");
            verify(mfa).enforcePasswordGrant(any(), eq(USER), eq(APP), any(), eq("/oauth2/token"));
            verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.TOKEN_REFRESHED));
        }

        @Test
        void revokedUsersRefreshIsRefusedAndTheSessionEnded() throws Exception {
            keycloakIssues(userToken());
            doThrow(ApiException.forbidden("access_denied", "Access denied. User 'jdoe' is explicitly denied access."))
                    .when(access).enforceLogin(eq(USER), eq(APP), any(), eq("refresh_token"));
            mvc.perform(refresh()).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("access_denied"))
                    .andExpect(jsonPath("$.access_token").doesNotExist());
            assertThat(logoutCalls()).isEqualTo(1);
            KC.verify(postRequestedFor(urlEqualTo(OIDC + "/logout")).withFormParam("refresh_token", equalTo("kc-refresh-2")));
            verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.TOKEN_REJECTED && "denied".equals(e.outcome())));
        }

        @Test
        void expiredRefreshIsKeycloaksInvalidGrant() throws Exception {
            keycloakRefuses("refresh_token", "kc-refresh-1", 400, "invalid_grant", "Token is not active");
            mvc.perform(refresh()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        }
    }

    // ---- client_credentials, discovery, logout, jwks, introspection ------------------------

    @Test
    void clientCredentialsIsForwardedWithoutUserGates() throws Exception {
        KC.stubFor(WireMock.post(urlEqualTo(OIDC + "/token")).withFormParam("grant_type", equalTo("client_credentials"))
                .willReturn(json(200, Map.of("access_token", "svc", "token_type", "Bearer", "expires_in", 60))));
        mvc.perform(tokenForm().header(HttpHeaders.AUTHORIZATION, basic(APP, APP_SECRET))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access_token").value("svc"));
        KC.verify(postRequestedFor(urlEqualTo(OIDC + "/token")).withFormParam("scope", equalTo("openid")));
        verify(access, never()).enforceLogin(any(), any(), any(), any());
    }

    @Test
    void discoveryPointsAtTheWrapperWithKeycloaksIssuer() throws Exception {
        mvc.perform(get("/oauth2/.well-known/openid-configuration")).andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(KC.baseUrl() + "/realms/government-internal"))
                .andExpect(jsonPath("$.authorization_endpoint").value("https://sso.gov.rw/oauth2/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value("https://sso.gov.rw/oauth2/token"))
                .andExpect(jsonPath("$.jwks_uri").value("https://sso.gov.rw/oauth2/jwks"))
                .andExpect(jsonPath("$.end_session_endpoint").value("https://sso.gov.rw/oauth2/logout"))
                .andExpect(jsonPath("$.introspection_endpoint").value("https://sso.gov.rw/oauth2/introspect"))
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"))
                .andExpect(jsonPath("$.code_challenge_methods_supported.length()").value(1));
    }

    @Test
    void jwksIsProxied() throws Exception {
        mvc.perform(get("/oauth2/jwks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value(GatewayTestKeys.KID));
    }

    @Test
    void jwksUpstreamFailureIs502() throws Exception {
        KC.stubFor(WireMock.get(urlEqualTo(OIDC + "/certs")).atPriority(1).willReturn(aResponse().withStatus(500)));
        mvc.perform(get("/oauth2/jwks")).andExpect(status().isBadGateway());
    }

    @Test
    void logoutValidatesThePostLogoutRedirect() throws Exception {
        mvc.perform(get("/oauth2/logout").param("client_id", APP)
                        .param("post_logout_redirect_uri", "https://kubaka.minagri.gov.rw/bye").param("state", "s1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        startsWith("https://login.gov.rw/realms/government-internal/protocol/openid-connect/logout?")))
                .andExpect(header().string("Location", containsString("client_id=" + APP)));
        mvc.perform(get("/oauth2/logout").param("client_id", APP).param("post_logout_redirect_uri", "https://evil.example/"))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Location"));
        mvc.perform(get("/oauth2/logout").param("post_logout_redirect_uri", "https://kubaka.minagri.gov.rw/bye"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/oauth2/logout")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"));
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.LOGOUT_REDIRECT_DENIED));
    }

    @Test
    void introspectionRequiresAnAuthenticatedConfidentialClient() throws Exception {
        KC.stubFor(WireMock.post(urlEqualTo(OIDC + "/token/introspect"))
                .willReturn(json(200, Map.of("active", true, "username", USER))));
        mvc.perform(post("/oauth2/introspect").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", "t").param("client_id", APP))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_client"));
        mvc.perform(post("/oauth2/introspect").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", "t").param("client_id", SPA))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/oauth2/introspect").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(APP, APP_SECRET)).param("token", "t"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        KC.verify(1, postRequestedFor(urlEqualTo(OIDC + "/token/introspect"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(basic(APP, APP_SECRET))));
    }

    @Test
    void keycloakUnreachableIs503() throws Exception {
        KC.stubFor(WireMock.post(urlEqualTo(OIDC + "/token")).willReturn(aResponse()
                .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
        mvc.perform(tokenForm().header(HttpHeaders.AUTHORIZATION, basic(APP, APP_SECRET))
                        .param("grant_type", "refresh_token").param("refresh_token", "r"))
                .andExpect(status().is(HttpStatus.SERVICE_UNAVAILABLE.value()))
                .andExpect(jsonPath("$.error").value(not("invalid_grant")));
        verify(access, never()).enforceLogin(anyString(), anyString(), any(), any());
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
