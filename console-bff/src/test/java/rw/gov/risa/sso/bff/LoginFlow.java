package rw.gov.risa.sso.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Drives the real browser dance through MockMvc against the WireMock wrapper:
 * /bff/login → /oauth2/authorization/sso-admin-ui → (wrapper authorize, simulated) → callback →
 * code exchange at the wrapper's /oauth2/token (client_secret_post + code_verifier) → id_token checked
 * against the wrapper's JWKS. Works with a container session (MockHttpSession) or a cookie-only
 * session (Spring Session / Redis), by carrying both.
 */
public final class LoginFlow {
    public final Map<String, Cookie> cookies = new LinkedHashMap<>();
    public MockHttpSession session;
    public String codeChallenge;
    public String location;
    private final MockMvc mvc;

    public LoginFlow(MockMvc mvc) {
        this.mvc = mvc;
    }

    public MockHttpServletRequestBuilder with(MockHttpServletRequestBuilder b) {
        if (session != null) {
            b = b.session(session);
        }
        if (!cookies.isEmpty()) {
            b = b.cookie(cookies.values().toArray(Cookie[]::new));
        }
        return b;
    }

    public MvcResult perform(MockHttpServletRequestBuilder b) throws Exception {
        MvcResult r = mvc.perform(with(b)).andReturn();
        MockHttpServletResponse res = r.getResponse();
        for (Cookie c : res.getCookies()) {
            if (c.getMaxAge() == 0) {
                cookies.remove(c.getName());
            } else {
                cookies.put(c.getName(), c);
            }
        }
        if (r.getRequest().getSession(false) instanceof MockHttpSession s && !s.isInvalid()) {
            session = s;
        }
        return r;
    }

    /** Logs in as alice with the given realm roles; returns where the callback redirected. */
    public String login(String returnTo, List<String> roles) throws Exception {
        var start = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/bff/login");
        if (returnTo != null) {
            start = start.param("returnTo", returnTo);
        }
        perform(start);
        MvcResult authorize = perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/oauth2/authorization/sso-admin-ui"));
        String redirect = authorize.getResponse().getRedirectedUrl();
        assertThat(redirect).startsWith(BffTestSupport.PUBLIC_WRAPPER + "/oauth2/authorize");
        Map<String, List<String>> q = UriComponentsBuilder.fromUriString(redirect).build().getQueryParams();
        String state = URLDecoder.decode(q.get("state").get(0), StandardCharsets.UTF_8);
        String nonce = URLDecoder.decode(q.get("nonce").get(0), StandardCharsets.UTF_8);
        codeChallenge = q.get("code_challenge").get(0);

        Instant now = Instant.now();
        String idToken = BffTestSupport.jwt(Map.of("iss", "http://keycloak.test/realms/government-admin",
                "sub", "sub-alice", "aud", "sso-admin-ui", "azp", "sso-admin-ui", "nonce", nonce,
                "preferred_username", "alice", "iat", now, "exp", now.plusSeconds(300)));
        String access = BffTestSupport.accessToken("alice", roles, now.plusSeconds(300));
        BffTestSupport.WRAPPER.stubFor(WireMock.post(urlEqualTo("/oauth2/token"))
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("code=the-code"))
                .withRequestBody(containing("client_secret=" + BffTestSupport.SECRET))
                .withRequestBody(containing("code_verifier="))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"access_token":"%s","token_type":"Bearer","expires_in":300,
                         "refresh_token":"refresh-1","id_token":"%s","scope":"openid profile email"}"""
                        .formatted(access, idToken))));

        MvcResult callback = perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/login/oauth2/code/sso-admin-ui").param("code", "the-code").param("state", state));
        location = callback.getResponse().getRedirectedUrl();
        return location;
    }

    /** The code_verifier the BFF sent must hash (S256) to the challenge it put in the authorize URL. */
    public void assertPkceVerifierMatchesChallenge() throws Exception {
        List<LoggedRequest> exchanges = BffTestSupport.WRAPPER.findAll(postRequestedFor(urlEqualTo("/oauth2/token"))
                .withRequestBody(containing("grant_type=authorization_code")));
        assertThat(exchanges).hasSize(1);
        String verifier = null;
        for (String pair : exchanges.get(0).getBodyAsString().split("&")) {
            if (pair.startsWith("code_verifier=")) {
                verifier = URLDecoder.decode(pair.substring("code_verifier=".length()), StandardCharsets.UTF_8);
            }
        }
        assertThat(verifier).isNotNull();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(digest)).isEqualTo(codeChallenge);
    }

    public Cookie xsrf() {
        return cookies.get("XSRF-TOKEN");
    }
}
