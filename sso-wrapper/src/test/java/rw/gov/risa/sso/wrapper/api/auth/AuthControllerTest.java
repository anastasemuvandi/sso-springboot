package rw.gov.risa.sso.wrapper.api.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.service.gateway.GatewayErrors;
import rw.gov.risa.sso.wrapper.service.gateway.GatewayMvcTestBase;

/** {@code /auth/*}: login through the gates, logout, the fail-closed verify, userinfo. */
class AuthControllerTest extends GatewayMvcTestBase {

    static MockHttpServletRequestBuilder login(String clientId, String secret) {
        String s = secret == null ? "" : ",\"client_secret\":\"" + secret + "\"";
        return post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + USER + "\",\"password\":\"Passw0rd-123\",\"client_id\":\"" + clientId + "\"" + s + "}");
    }

    // ---- login -----------------------------------------------------------------------------

    @Test
    void loginReturnsTheReferenceTokenResponse() throws Exception {
        String token = userToken();
        keycloakIssues(token);
        mvc.perform(login(APP, APP_SECRET)).andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value(token))
                .andExpect(jsonPath("$.refresh_token").value("kc-refresh-2"))
                .andExpect(jsonPath("$.id_token").value("kc-id"))
                .andExpect(jsonPath("$.token_type").value("bearer"))
                .andExpect(jsonPath("$.redirect_url").value(REDIRECT));
        KC.verify(postRequestedFor(urlEqualTo(OIDC + "/token")).withFormParam("grant_type", equalTo("password"))
                .withFormParam("username", equalTo(USER)).withFormParam("client_secret", equalTo(APP_SECRET)));
        verify(access).enforceLogin(USER, APP, "127.0.0.1", "password");
        verify(joinKeys).enforceLogin(USER, APP, "127.0.0.1", "password");
        verify(mfa).enforcePasswordGrant(any(), eq(USER), eq(APP), any(), eq("/auth/login"));
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.LOGIN_SUCCESS && USER.equals(e.actor())
                && APP.equals(e.clientId())));
    }

    @Test
    void confidentialClientMustPresentItsSecret() throws Exception {
        keycloakIssues(userToken());
        mvc.perform(login(APP, null)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
        KC.verify(0, postRequestedFor(urlEqualTo(OIDC + "/token")));
        verify(directory, never()).secretMatches(any(), any());
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.LOGIN_FAILURE));
    }

    @Test
    void secretMayComeInHttpBasic() throws Exception {
        keycloakIssues(userToken());
        mvc.perform(login(APP, null).header(HttpHeaders.AUTHORIZATION, basic(APP, APP_SECRET)))
                .andExpect(status().isOk());
    }

    @Test
    void publicClientLogsInWithoutSecret() throws Exception {
        keycloakIssues(token().client(SPA).sign());
        mvc.perform(login(SPA, null)).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordIs401AndAudited() throws Exception {
        keycloakRefuses("username", USER, 401, "invalid_grant", "Invalid user credentials");
        mvc.perform(login(APP, APP_SECRET)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(GatewayErrors.INVALID_CREDENTIALS))
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.LOGIN_FAILURE && USER.equals(e.actor())));
        verify(access, never()).enforceLogin(any(), any(), any(), any());
    }

    @Test
    void refusedLoginEndsTheMintedSessionAndReturnsNoToken() throws Exception {
        keycloakIssues(userToken());
        doThrow(ApiException.forbidden("access_denied", "Access denied. User 'jdoe' is not assigned to client."))
                .when(access).enforceLogin(eq(USER), eq(APP), any(), any());
        mvc.perform(login(APP, APP_SECRET)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("access_denied"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
        assertThat(logoutCalls()).isEqualTo(1);
        verify(audit, never()).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.LOGIN_SUCCESS));
    }

    @Test
    void missingFieldsAre422() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- verify (fails closed) -------------------------------------------------------------

    @Test
    void verifyWithoutBearerIs401() throws Exception {
        mvc.perform(get("/auth/verify")).andExpect(status().isUnauthorized());
    }

    @Test
    void verifyForgedOrExpiredTokenIs401() throws Exception {
        mvc.perform(get("/auth/verify").header(HttpHeaders.AUTHORIZATION, "Bearer " + token().signedByAnotherKey().sign()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/verify").header(HttpHeaders.AUTHORIZATION, "Bearer " + token().expired().sign()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/verify").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifyWithoutPreferredUsernameIs401() throws Exception {
        mvc.perform(get("/auth/verify").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + token().claim("preferred_username", null).sign()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifyWhenAzpCannotBeCheckedIs401() throws Exception {
        when(knownClients.isKnown(anyString())).thenThrow(new IllegalStateException("keycloak down"));
        mvc.perform(get("/auth/verify").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifyChecksTheSessionAndFailsClosed() throws Exception {
        when(directory.sessionActive(anyString(), anyString())).thenReturn(true);
        mvc.perform(get("/auth/verify").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));

        when(directory.sessionActive(anyString(), anyString())).thenReturn(false);
        mvc.perform(get("/auth/verify").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false));

        when(directory.sessionActive(anyString(), anyString())).thenThrow(GatewayErrors.upstream("down"));
        mvc.perform(get("/auth/verify").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false));
    }

    // ---- logout ----------------------------------------------------------------------------

    @Test
    void logoutEndsTheTokensSessionAndIsAudited() throws Exception {
        String token = token().claim("sid", "sess-1").sign();
        mvc.perform(post("/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Successfully logged out"));
        verify(directory).endSession("sess-1");
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.LOGOUT && USER.equals(e.actor())
                && "sess-1".equals(e.session())));
    }

    @Test
    void logoutWithoutBearerIs401() throws Exception {
        mvc.perform(post("/auth/logout")).andExpect(status().isUnauthorized());
        verify(directory, never()).endSession(any());
    }

    @Test
    void logoutWithForgedBearerIs401() throws Exception {
        mvc.perform(post("/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token().signedByAnotherKey().sign()))
                .andExpect(status().isUnauthorized());
        verify(directory, never()).endSession(any());
    }

    // ---- userinfo --------------------------------------------------------------------------

    @Test
    void userinfoHasTheReferenceShape() throws Exception {
        GroupRepresentation g = new GroupRepresentation();
        g.setId("g1");
        g.setName("minagri");
        when(directory.userGroups(anyString())).thenReturn(List.of(g));
        ClientRepresentation c = new ClientRepresentation();
        c.setId("uuid-app");
        c.setClientId(APP);
        c.setName("Kubaka");
        ClientRepresentation rm = new ClientRepresentation();
        rm.setId("uuid-rm");
        rm.setClientId("realm-management");
        when(directory.clients()).thenReturn(List.of(c, rm));
        String token = token().roles("default-roles-government-internal", "inst:minagri:staff")
                .claim("resource_access", Map.of(APP, Map.of("roles", List.of("viewer")),
                        "realm-management", Map.of("roles", List.of("view-users"))))
                .claim("given_name", "Jane").claim("family_name", "Doe").claim("scope", "openid email").sign();
        mvc.perform(get("/auth/userinfo").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USER))
                .andExpect(jsonPath("$.first_name").value("Jane"))
                .andExpect(jsonPath("$.realm_roles.length()").value(1))
                .andExpect(jsonPath("$.realm_roles[0]").value("inst:minagri:staff"))
                .andExpect(jsonPath("$.client_roles[0]").value("viewer"))
                .andExpect(jsonPath("$.agencies[0].name").value("minagri"))
                .andExpect(jsonPath("$.clients.length()").value(1))
                .andExpect(jsonPath("$.clients[0].clientId").value(APP))
                .andExpect(jsonPath("$.current_client.clientId").value(APP))
                .andExpect(jsonPath("$.client_id").value(APP))
                .andExpect(jsonPath("$.scope").value("openid email"));
    }
}
