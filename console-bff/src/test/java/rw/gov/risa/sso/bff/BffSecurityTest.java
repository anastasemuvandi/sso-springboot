package rw.gov.risa.sso.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.client.WireMock;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import rw.gov.risa.sso.bff.security.ReturnTo;

class BffSecurityTest extends BffTestSupport {

    // ------------------------------------------------------------------ 401 JSON, never a redirect

    @Test
    void sessionWithoutLoginIs401Json() throws Exception {
        mvc.perform(get("/bff/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.error").value("unauthenticated"))
                .andExpect(jsonPath("$.details.login_url").value("/bff/login"));
    }

    @Test
    void apiWithoutLoginIs401JsonAndNothingIsRelayed() throws Exception {
        mvc.perform(get("/api/users").header("Accept", "text/html"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.error").value("unauthenticated"));
        assertThat(WRAPPER.getAllServeEvents()).isEmpty();
    }

    // ------------------------------------------------------------------ /bff/session (cosmetic)

    @Test
    void sessionDescribesAPlatformAdmin() throws Exception {
        MockHttpSession session = freshSession("root", List.of("super_admin", "inst:minagri:admin", "offline_access"));
        mvc.perform(get("/bff/session").session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("root"))
                .andExpect(jsonPath("$.name").value("Test root"))
                .andExpect(jsonPath("$.platformAdmin").value(true))
                .andExpect(jsonPath("$.platformRoles[0]").value("super_admin"))
                .andExpect(jsonPath("$.institutionSlug").value("minagri"))
                .andExpect(jsonPath("$.landing").value("/dashboard"))
                .andExpect(jsonPath("$.roles[0]").value("inst:minagri:admin"))
                .andExpect(jsonPath("$.access_token").doesNotExist())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void sessionDescribesAnInstitutionAdmin() throws Exception {
        MockHttpSession session = freshSession("hr", List.of("inst:MINAGRI:hr", "default-roles-government-internal"));
        mvc.perform(get("/bff/session").session(session))
                .andExpect(jsonPath("$.platformAdmin").value(false))
                .andExpect(jsonPath("$.institutionAdmin").value(true))
                .andExpect(jsonPath("$.institutionSlug").value("minagri"))
                .andExpect(jsonPath("$.institutions[0]").value("minagri"))
                .andExpect(jsonPath("$.landing").value("/institution"));
    }

    @Test
    void sessionForSomeoneWithNoAdminRoleLandsOnNoAccess() throws Exception {
        mvc.perform(get("/bff/session").session(freshSession("staff", List.of("offline_access"))))
                .andExpect(jsonPath("$.platformAdmin").value(false))
                .andExpect(jsonPath("$.institutionAdmin").value(false))
                .andExpect(jsonPath("$.landing").value("/no-access"));
    }

    // ------------------------------------------------------------------ login: PKCE S256 via the wrapper

    @Test
    void loginStartsAuthorizationCodeWithPkceAtTheWrapper() throws Exception {
        MvcResult start = mvc.perform(get("/bff/login").param("returnTo", "/clients/abc?tab=2"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/authorization/sso-admin-ui"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) start.getRequest().getSession();
        assertThat(session.getAttribute(ReturnTo.SESSION_ATTRIBUTE)).isEqualTo("/clients/abc?tab=2");

        String location = mvc.perform(get("/oauth2/authorization/sso-admin-ui").session(session))
                .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        assertThat(location).startsWith(PUBLIC_WRAPPER + "/oauth2/authorize?");
        Map<String, List<String>> q = UriComponentsBuilder.fromUriString(location).build(true).getQueryParams();
        assertThat(q.get("response_type")).containsExactly("code");
        assertThat(q.get("client_id")).containsExactly("sso-admin-ui");
        assertThat(q.get("code_challenge_method")).containsExactly("S256");
        assertThat(q.get("code_challenge").get(0)).hasSizeGreaterThanOrEqualTo(43);
        assertThat(q.get("state")).isNotEmpty();
        assertThat(q.get("nonce")).isNotEmpty();
        assertThat(q.get("scope").get(0)).contains("openid");
        assertThat(q.get("redirect_uri").get(0)).endsWith("/login/oauth2/code/sso-admin-ui");
    }

    @Test
    void returnToCannotLeaveTheOrigin() throws Exception {
        for (String evil : List.of("//evil.example", "https://evil.example", "/\\evil.example", "/bff/logout",
                "/api/users", "javascript:alert(1)")) {
            MvcResult r = mvc.perform(get("/bff/login").param("returnTo", evil)).andExpect(status().isFound()).andReturn();
            MockHttpSession session = (MockHttpSession) r.getRequest().getSession(false);
            assertThat(session == null ? null : session.getAttribute(ReturnTo.SESSION_ATTRIBUTE)).as(evil).isNull();
        }
    }

    // ------------------------------------------------------------------ CSRF double submit

    private Cookie xsrfCookie() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/")).andReturn().getResponse();
        Cookie c = res.getCookie("XSRF-TOKEN");
        assertThat(c).isNotNull();
        assertThat(c.isHttpOnly()).isFalse();
        assertThat(c.getSecure()).isTrue();
        assertThat(c.getAttribute("SameSite")).isEqualTo("Lax");
        return c;
    }

    @Test
    void stateChangingApiCallWithoutXsrfHeaderIs403AndNotRelayed() throws Exception {
        MockHttpSession session = freshSession("root", List.of("super_admin"));
        Cookie xsrf = xsrfCookie();
        mvc.perform(post("/api/users").session(session).cookie(xsrf).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("csrf_or_access_denied"));
        mvc.perform(post("/api/users").session(session).cookie(xsrf).header("X-XSRF-TOKEN", "forged")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        assertThat(WRAPPER.findAll(WireMock.postRequestedFor(WireMock.urlEqualTo("/users")))).isEmpty();
    }

    @Test
    void stateChangingApiCallWithDoubleSubmitIsRelayed() throws Exception {
        WRAPPER.stubFor(WireMock.post(WireMock.urlEqualTo("/users"))
                .willReturn(WireMock.aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"u1\"}")));
        MockHttpSession session = freshSession("root", List.of("super_admin"));
        Cookie xsrf = xsrfCookie();
        mvc.perform(post("/api/users").session(session).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType("application/json").content("{\"username\":\"new.user\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("u1"));
        WRAPPER.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/users"))
                .withRequestBody(WireMock.equalToJson("{\"username\":\"new.user\"}"))
                .withoutHeader("X-XSRF-TOKEN")
                .withoutHeader("Cookie"));
    }

    // ------------------------------------------------------------------ headers + SPA serving

    @Test
    void securityHeadersAreSet() throws Exception {
        mvc.perform(get("/"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("form-action 'self' " + PUBLIC_WRAPPER)))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                .andExpect(header().exists("Permissions-Policy"));
    }

    @Test
    void spaIsServedWithHistoryFallback() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(containsString("console")));
        mvc.perform(get("/institution/access/matrix")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<div id=root>")))
                .andExpect(header().string("Cache-Control", containsString("no-cache")));
        mvc.perform(get("/favicon.svg")).andExpect(status().isOk()).andExpect(content().string("<svg/>"));
        mvc.perform(get("/assets/app-abc123.js")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=31536000")));
    }

    @Test
    void noFallbackForReservedPrefixesOrMissingAssets() throws Exception {
        for (String path : List.of("/bff/nope", "/oauth2/nope", "/login", "/login/nope", "/actuator/nope",
                "/assets/missing.js", "/missing.png")) {
            String body = mvc.perform(get(path)).andReturn().getResponse().getContentAsString();
            assertThat(body).as(path).doesNotContain("<div id=root>");
        }
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
        mvc.perform(get("/bff/nope")).andExpect(status().isNotFound());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
