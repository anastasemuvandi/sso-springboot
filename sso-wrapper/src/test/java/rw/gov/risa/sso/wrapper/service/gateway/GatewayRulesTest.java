package rw.gov.risa.sso.wrapper.service.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;

import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.error.ApiException;

/** The gateway's pure rules: PKCE, redirect matching, client authentication, the account back link. */
class GatewayRulesTest {

    @Nested
    class PkceRules {
        // RFC 7636 Appendix B
        static final String V = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        static final String C = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

        @Test
        void s256MatchesTheRfcVector() {
            assertThat(Pkce.s256(V)).isEqualTo(C);
            assertThat(Pkce.verify(V, C, "S256")).isTrue();
        }

        @Test
        void plainIsNeverAccepted() {
            assertThat(Pkce.verify(V, V, "plain")).isFalse();
            assertThat(Pkce.verify(V, C, "plain")).isFalse();
            assertThat(Pkce.verify(V, C, null)).isFalse();
        }

        @Test
        void verifierShapeIsEnforced() {
            assertThat(Pkce.isValidVerifier("short")).isFalse();
            assertThat(Pkce.isValidVerifier("a".repeat(129))).isFalse();
            assertThat(Pkce.isValidVerifier("a".repeat(42) + "!")).isFalse();
            assertThat(Pkce.isValidVerifier(V)).isTrue();
            assertThat(Pkce.verify("x".repeat(43), C, "S256")).isFalse();
            assertThat(Pkce.isValidChallenge(C)).isTrue();
            assertThat(Pkce.isValidChallenge(C + "=")).isFalse();
        }
    }

    @Nested
    class Redirects {
        List<String> reg = List.of("https://app.gov.rw/cb", "http://localhost:3001/*");

        @Test
        void exactOnlyByDefault() {
            assertThat(RedirectUris.isRegistered("https://app.gov.rw/cb", reg, false)).isTrue();
            assertThat(RedirectUris.isRegistered("https://app.gov.rw/cb/", reg, false)).isFalse();
            assertThat(RedirectUris.isRegistered("https://app.gov.rw/CB", reg, false)).isFalse();
            assertThat(RedirectUris.isRegistered("https://app.gov.rw/cb?x=1", reg, false)).isFalse();
            assertThat(RedirectUris.isRegistered("http://localhost:3001/x", reg, false)).isFalse();
            assertThat(RedirectUris.isRegistered(null, reg, false)).isFalse();
        }

        @Test
        void wildcardOnlyWhenEnabledAndAtAPathBoundary() {
            assertThat(RedirectUris.isRegistered("http://localhost:3001/x", reg, true)).isTrue();
            assertThat(RedirectUris.isRegistered("http://localhost:30011/x", List.of("http://localhost:3001*"), true))
                    .isFalse();
        }

        @Test
        void postLogoutUrisFollowKeycloaksConvention() {
            ClientRepresentation c = new ClientRepresentation();
            c.setRedirectUris(List.of("https://a/cb"));
            c.setAttributes(Map.of(RegisteredClient.POST_LOGOUT_ATTR, "+"));
            assertThat(RegisteredClient.of(c).postLogoutRedirectUris()).containsExactly("https://a/cb");
            c.setAttributes(Map.of(RegisteredClient.POST_LOGOUT_ATTR, "https://a/bye##https://a/out"));
            assertThat(RegisteredClient.of(c).postLogoutRedirectUris()).containsExactly("https://a/bye", "https://a/out");
        }
    }

    @Nested
    class ClientAuth {
        GatewayDirectory dir = mock(GatewayDirectory.class);
        ClientAuthenticator auth = new ClientAuthenticator(dir);
        RegisteredClient conf = new RegisteredClient("u1", "conf", "c", true, false, false, List.of(), List.of(), null, null);
        RegisteredClient pub = new RegisteredClient("u2", "pub", "p", true, true, false, List.of(), List.of(), null, null);

        String basic(String raw) {
            return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void basicIsUrlDecodedPerRfc6749() {
            var p = ClientAuthenticator.presented(basic("conf:a%2Bb%3Ac"), null, null);
            assertThat(p.clientId()).isEqualTo("conf");
            assertThat(p.secret()).isEqualTo("a+b:c");
            assertThat(p.basic()).isTrue();
            assertThat(p.toString()).doesNotContain("a+b");
        }

        @Test
        void conflictingMethodsAreRefused() {
            assertThatThrownBy(() -> ClientAuthenticator.presented(basic("conf:s"), null, "s"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("invalid_request"));
            assertThatThrownBy(() -> ClientAuthenticator.presented(basic("conf:s"), "other", null))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("invalid_request"));
            assertThatThrownBy(() -> ClientAuthenticator.presented("Basic !!!", null, null))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("invalid_client"));
        }

        @Test
        void confidentialClientMustPresentTheRightSecret() {
            when(dir.findClient("conf")).thenReturn(Optional.of(conf));
            when(dir.secretMatches(conf, "right")).thenReturn(true);
            assertThatThrownBy(() -> auth.authenticate(new ClientAuthenticator.Presented("conf", null, false), false))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status().value()).isEqualTo(401);
                        assertThat(e.error()).isEqualTo("invalid_client");
                    });
            verify(dir, never()).secretMatches(any(), any());
            assertThatThrownBy(() -> auth.authenticate(new ClientAuthenticator.Presented("conf", "wrong", false), false))
                    .isInstanceOf(ApiException.class);
            assertThat(auth.authenticate(new ClientAuthenticator.Presented("conf", "right", true), true)).isEqualTo(conf);
        }

        @Test
        void publicAndUnknownClients() {
            when(dir.findClient("pub")).thenReturn(Optional.of(pub));
            when(dir.findClient("ghost")).thenReturn(Optional.empty());
            assertThat(auth.authenticate(new ClientAuthenticator.Presented("pub", null, false), false)).isEqualTo(pub);
            assertThatThrownBy(() -> auth.authenticate(new ClientAuthenticator.Presented("pub", null, false), true))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> auth.authenticate(new ClientAuthenticator.Presented("ghost", "x", false), false))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("invalid_client"));
            assertThatThrownBy(() -> auth.authenticate(new ClientAuthenticator.Presented(null, "x", false), false))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("invalid_request"));
            verify(dir, never()).secretMatches(eq(pub), any());
        }
    }

    @Nested
    class BackLink {
        AccountPageLinks links = new AccountPageLinks(new SsoProperties(false, "dev", "https://sso.gov.rw",
                new SsoProperties.Keycloak("http://keycloak:8080", "https://login.gov.rw", "https://login.gov.rw",
                        "government-internal", "sso-client", "", List.of()), new SsoProperties.Cors(List.of())));

        @Test
        void relativeRestartUrlIsResolvedOnKeycloak() {
            String rel = "/realms/government-internal/login-actions/restart?client_id=x&tab_id=1";
            assertThat(links.acceptedReturnTo(rel)).isEqualTo(rel);
            assertThat(links.backUrl(rel)).isEqualTo("https://login.gov.rw" + rel);
        }

        @Test
        void absoluteOrOffSiteIsDropped() {
            for (String bad : List.of("https://evil.example/x", "https://login.gov.rw/realms/x", "//evil.example/x",
                    "/\\evil.example", "javascript:alert(1)", "realms/x", "/x\r\nSet-Cookie:a")) {
                assertThat(links.acceptedReturnTo(bad)).as(bad).isNull();
                assertThat(links.backUrl(links.acceptedReturnTo(bad))).isNull();
            }
        }
    }
}
