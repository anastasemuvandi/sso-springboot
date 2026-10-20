package rw.gov.risa.sso.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

class LoginFlowTest extends BffTestSupport {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fullLoginStoresTokensServerSideAndReturnsToTheRequestedPage() throws Exception {
        LoginFlow flow = new LoginFlow(mvc);
        assertThat(flow.login("/clients/abc", List.of("super_admin"))).isEqualTo("/clients/abc");
        flow.assertPkceVerifierMatchesChallenge();

        MvcResult session = flow.perform(get("/bff/session"));
        assertThat(session.getResponse().getStatus()).isEqualTo(200);
        String body = session.getResponse().getContentAsString();
        assertThat(mapper.readTree(body).get("username").asText()).isEqualTo("alice");
        // No token ever reaches the browser: not in the body, not in a cookie.
        assertThat(body).doesNotContain("refresh-1").doesNotContain("eyJ");
        flow.cookies.values().forEach(c -> assertThat(c.getValue()).doesNotStartWith("eyJ"));
    }

    @Test
    void withoutReturnToThePlatformAdminLandsOnTheDashboard() throws Exception {
        assertThat(new LoginFlow(mvc).login(null, List.of("admin"))).isEqualTo("/dashboard");
    }

    @Test
    void withoutReturnToTheInstitutionAdminLandsOnTheInstitutionConsole() throws Exception {
        assertThat(new LoginFlow(mvc).login(null, List.of("inst:minagri:admin"))).isEqualTo("/institution");
    }

    @Test
    void aStateMismatchFailsTheLogin() throws Exception {
        LoginFlow flow = new LoginFlow(mvc);
        flow.perform(get("/oauth2/authorization/sso-admin-ui"));
        MvcResult r = flow.perform(get("/login/oauth2/code/sso-admin-ui").param("code", "c").param("state", "forged"));
        assertThat(r.getResponse().getRedirectedUrl()).startsWith("/?error=");
        assertThat(flow.perform(get("/bff/session")).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aWrapperRefusalSurfacesItsErrorCode() throws Exception {
        LoginFlow flow = new LoginFlow(mvc);
        flow.perform(get("/oauth2/authorization/sso-admin-ui"));
        String redirect = flow.perform(get("/oauth2/authorization/sso-admin-ui")).getResponse().getRedirectedUrl();
        String state = UriComponentsBuilder.fromUriString(redirect).build().getQueryParams().getFirst("state");
        MvcResult r = flow.perform(get("/login/oauth2/code/sso-admin-ui")
                .param("error", "access_denied").param("state", java.net.URLDecoder.decode(state, "UTF-8")));
        assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/?error=access_denied");
    }

    @Test
    @SuppressWarnings("unchecked")
    void logoutEndsTheLocalSessionAndHandsBackTheRpInitiatedLogoutUrl() throws Exception {
        LoginFlow flow = new LoginFlow(mvc);
        flow.login("/dashboard", List.of("super_admin"));
        flow.perform(get("/"));
        assertThat(flow.xsrf()).isNotNull();

        // Without the XSRF header the logout is refused (no logout CSRF).
        assertThat(flow.perform(post("/bff/logout").header("Accept", "application/json")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(flow.perform(get("/bff/session")).getResponse().getStatus()).isEqualTo(200);

        MvcResult out = flow.perform(post("/bff/logout").header("Accept", "application/json")
                .header("X-XSRF-TOKEN", flow.xsrf().getValue()));
        assertThat(out.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> body = mapper.readValue(out.getResponse().getContentAsString(), Map.class);
        String url = (String) body.get("logout_url");
        assertThat(url).startsWith(PUBLIC_WRAPPER + "/oauth2/logout?");
        var q = UriComponentsBuilder.fromUriString(url).build().getQueryParams();
        assertThat(q.getFirst("client_id")).isEqualTo("sso-admin-ui");
        assertThat(q.getFirst("post_logout_redirect_uri")).contains("console.example.test");
        assertThat(q.getFirst("id_token_hint")).startsWith("eyJ");
        assertThat(out.getResponse().getHeaders("Set-Cookie")).anyMatch(h -> h.startsWith("GORSSO_BFF_SESSION=;")
                || h.startsWith("GORSSO_BFF_SESSION=") && h.contains("Max-Age=0"));

        assertThat(flow.perform(get("/bff/session")).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aFormLogoutIsRedirectedToTheWrapper() throws Exception {
        LoginFlow flow = new LoginFlow(mvc);
        flow.login("/dashboard", List.of("super_admin"));
        flow.perform(get("/"));
        // console-web's SignOutForm: a top-level form POST carrying the raw cookie value as _csrf.
        assertThat(flow.perform(post("/bff/logout").param("_csrf", "forged")).getResponse().getStatus()).isEqualTo(403);
        MvcResult out = flow.perform(post("/bff/logout").param("_csrf", flow.xsrf().getValue()));
        assertThat(out.getResponse().getStatus()).isEqualTo(302);
        assertThat(out.getResponse().getRedirectedUrl()).startsWith(PUBLIC_WRAPPER + "/oauth2/logout?client_id=sso-admin-ui");
    }
}
