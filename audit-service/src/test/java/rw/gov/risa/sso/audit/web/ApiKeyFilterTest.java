package rw.gov.risa.sso.audit.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.audit.config.AuditProperties;

class ApiKeyFilterTest {

    private static ApiKeyFilter filter(String key, String extra) {
        AuditProperties p = new AuditProperties("dev", key, extra, new AuditProperties.Workers(false),
                new AuditProperties.Health(List.of(), Map.of(), null, Duration.ofSeconds(1)),
                new AuditProperties.Keycloak("http://kc", "r", "admin", null, "master", "admin-cli"),
                new AuditProperties.Retention(90));
        return new ApiKeyFilter(p, new ObjectMapper());
    }

    @Test
    void acceptsPrimaryAndRotationKeys() {
        ApiKeyFilter f = filter("primary", " old-1 , old-2 ");
        assertThat(f.matches("primary")).isTrue();
        assertThat(f.matches("old-2")).isTrue();
        assertThat(f.matches("primar")).isFalse();
        assertThat(f.matches("")).isFalse();
        assertThat(f.matches(null)).isFalse();
    }

    @Test
    void noConfiguredKeyFailsClosed() {
        assertThat(filter(null, null).matches("anything")).isFalse();
    }

    @Test
    void auditRoutesNeedTheKeyOthersDoNot() throws Exception {
        ApiKeyFilter f = filter("k", null);
        MockHttpServletResponse denied = new MockHttpServletResponse();
        f.doFilter(new MockHttpServletRequest("GET", "/audit/events"), denied, new MockFilterChain());
        assertThat(denied.getStatus()).isEqualTo(401);
        assertThat(denied.getContentAsString()).contains("\"error\":\"unauthorized\"");

        MockHttpServletRequest ok = new MockHttpServletRequest("GET", "/audit/events");
        ok.addHeader(ApiKeyFilter.HEADER, "k");
        MockHttpServletResponse okResp = new MockHttpServletResponse();
        f.doFilter(ok, okResp, new MockFilterChain());
        assertThat(okResp.getStatus()).isEqualTo(200);

        MockHttpServletResponse open = new MockHttpServletResponse();
        f.doFilter(new MockHttpServletRequest("GET", "/health"), open, new MockFilterChain());
        assertThat(open.getStatus()).isEqualTo(200);

        MockHttpServletResponse lookalike = new MockHttpServletResponse();
        f.doFilter(new MockHttpServletRequest("GET", "/auditx"), lookalike, new MockFilterChain());
        assertThat(lookalike.getStatus()).isEqualTo(200);
    }
}
