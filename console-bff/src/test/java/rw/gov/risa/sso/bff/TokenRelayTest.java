package rw.gov.risa.sso.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import com.github.tomakehurst.wiremock.client.WireMock;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;

import rw.gov.risa.sso.bff.config.BffProperties;

/** TokenRelay forwards the CALLER's own bearer, refreshing it through the wrapper when it expires. */
class TokenRelayTest extends BffTestSupport {

    private OAuth2AuthorizedClient storedClient(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return authorizedClients.loadAuthorizedClient(BffProperties.REGISTRATION_ID, null, request);
    }

    @Test
    void relaysTheCallersOwnBearerAndStripsTheApiPrefix() throws Exception {
        Instant now = Instant.now();
        String token = accessToken("alice", List.of("inst:minagri:admin"), now.plusSeconds(300));
        MockHttpSession session = loggedIn(token, now, now.plusSeconds(300), "refresh-1");
        WRAPPER.stubFor(WireMock.get(urlPathEqualTo("/institution-admin/context"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"institution\":\"minagri\"}")));

        mvc.perform(get("/api/institution-admin/context").param("x", "1").session(session)
                        .cookie(new Cookie("GORSSO_BFF_SESSION", "abc"))
                        .header("Authorization", "Bearer forged-by-the-browser")
                        .header("X-Correlation-Id", "corr-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institution").value("minagri"));

        WRAPPER.verify(getRequestedFor(urlEqualTo("/institution-admin/context?x=1"))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .withHeader("Cookie", absent())
                .withHeader("X-Correlation-Id", equalTo("corr-123")));
        WRAPPER.verify(0, postRequestedFor(urlEqualTo("/oauth2/token")));
    }

    @Test
    void anExpiredAccessTokenIsRefreshedThenRelayed() throws Exception {
        Instant now = Instant.now();
        String expired = accessToken("alice", List.of("super_admin"), now.minusSeconds(60));
        String fresh = accessToken("alice", List.of("super_admin"), now.plusSeconds(300));
        MockHttpSession session = loggedIn(expired, now.minusSeconds(360), now.minusSeconds(60), "refresh-1");

        WRAPPER.stubFor(WireMock.post(urlEqualTo("/oauth2/token"))
                .withRequestBody(containing("grant_type=refresh_token"))
                .withRequestBody(containing("refresh_token=refresh-1"))
                .withRequestBody(containing("client_id=sso-admin-ui"))
                .withRequestBody(containing("client_secret=" + SECRET))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"access_token":"%s","token_type":"Bearer","expires_in":300,"refresh_token":"refresh-2"}"""
                        .formatted(fresh))));
        WRAPPER.stubFor(WireMock.get(urlEqualTo("/users"))
                .withHeader("Authorization", equalTo("Bearer " + fresh))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[]")));

        mvc.perform(get("/api/users").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/users").session(session)).andExpect(status().isOk());

        WRAPPER.verify(1, postRequestedFor(urlEqualTo("/oauth2/token")));
        WRAPPER.verify(2, getRequestedFor(urlEqualTo("/users")).withHeader("Authorization", equalTo("Bearer " + fresh)));
        OAuth2AuthorizedClient stored = storedClient(session);
        assertThat(stored.getAccessToken().getTokenValue()).isEqualTo(fresh);
        assertThat(stored.getRefreshToken().getTokenValue()).isEqualTo("refresh-2");
    }

    @Test
    void aTokenWithinTheSkewIsRefreshedAhead() throws Exception {
        Instant now = Instant.now();
        String nearly = accessToken("alice", List.of("super_admin"), now.plusSeconds(10));
        String fresh = accessToken("alice", List.of("super_admin"), now.plusSeconds(300));
        MockHttpSession session = loggedIn(nearly, now.minusSeconds(290), now.plusSeconds(10), "refresh-1");
        WRAPPER.stubFor(WireMock.post(urlEqualTo("/oauth2/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"" + fresh + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        WRAPPER.stubFor(WireMock.get(urlEqualTo("/users")).willReturn(aResponse().withBody("[]")));

        mvc.perform(get("/api/users").session(session)).andExpect(status().isOk());
        WRAPPER.verify(getRequestedFor(urlEqualTo("/users")).withHeader("Authorization", equalTo("Bearer " + fresh)));
        // No new refresh token in the response: the old one is kept.
        assertThat(storedClient(session).getRefreshToken().getTokenValue()).isEqualTo("refresh-1");
    }

    @Test
    void aRefusedRefreshEndsTheSessionWith401() throws Exception {
        Instant now = Instant.now();
        MockHttpSession session = loggedIn(accessToken("alice", List.of("super_admin"), now.minusSeconds(60)),
                now.minusSeconds(360), now.minusSeconds(60), "refresh-dead");
        WRAPPER.stubFor(WireMock.post(urlEqualTo("/oauth2/token")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Token is not active\"}")));

        mvc.perform(get("/api/users").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("session_expired"));
        assertThat(session.isInvalid()).isTrue();
        WRAPPER.verify(0, getRequestedFor(urlEqualTo("/users")));
    }

    @Test
    void sessionEndpointAlsoRefreshesAndEndsADeadSession() throws Exception {
        Instant now = Instant.now();
        MockHttpSession session = loggedIn(accessToken("alice", List.of("super_admin"), now.minusSeconds(60)),
                now.minusSeconds(360), now.minusSeconds(60), "refresh-dead");
        WRAPPER.stubFor(WireMock.post(urlEqualTo("/oauth2/token")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_grant\"}")));
        mvc.perform(get("/bff/session").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("session_expired"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aWrapperOutageDuringRefreshIs502AndKeepsTheSession() throws Exception {
        Instant now = Instant.now();
        MockHttpSession session = loggedIn(accessToken("alice", List.of("super_admin"), now.minusSeconds(60)),
                now.minusSeconds(360), now.minusSeconds(60), "refresh-1");
        WRAPPER.stubFor(WireMock.post(urlEqualTo("/oauth2/token")).willReturn(aResponse().withStatus(503)));
        mvc.perform(get("/api/users").session(session))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("token_refresh_failed"));
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void anExpiredTokenWithNoRefreshTokenIs401() throws Exception {
        Instant now = Instant.now();
        MockHttpSession session = loggedIn(accessToken("alice", List.of("super_admin"), now.minusSeconds(60)),
                now.minusSeconds(360), now.minusSeconds(60), null);
        mvc.perform(get("/api/users").session(session)).andExpect(status().isUnauthorized());
        WRAPPER.verify(0, getRequestedFor(urlEqualTo("/users")));
    }

    @Test
    void upstreamErrorsPassThroughUnchanged() throws Exception {
        WRAPPER.stubFor(WireMock.get(urlEqualTo("/platform/overview")).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"forbidden\",\"message\":\"admin tier required\",\"details\":{}}")));
        mvc.perform(get("/api/platform/overview").session(freshSession("hr", List.of("inst:minagri:hr"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void anAuthenticatedSessionWithoutTokensIs401() throws Exception {
        MockHttpSession session = freshSession("alice", List.of("super_admin"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        authorizedClients.removeAuthorizedClient(BffProperties.REGISTRATION_ID, null, request,
                new org.springframework.mock.web.MockHttpServletResponse());
        mvc.perform(get("/api/users").session(session)).andExpect(status().isUnauthorized());
    }
}
