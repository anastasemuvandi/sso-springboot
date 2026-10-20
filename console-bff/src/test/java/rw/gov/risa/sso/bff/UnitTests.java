package rw.gov.risa.sso.bff;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import rw.gov.risa.sso.bff.security.ReturnTo;
import rw.gov.risa.sso.bff.session.SessionInfo;
import rw.gov.risa.sso.bff.session.SessionInfoService;

class UnitTests {

    @Test
    void returnToAcceptsOnlySameOriginPaths() {
        assertThat(ReturnTo.sanitize("/clients/x?tab=1#a")).isEqualTo("/clients/x?tab=1#a");
        assertThat(ReturnTo.sanitize("/institution")).isEqualTo("/institution");
        for (String bad : new String[] {null, "", "clients", "//evil", "/\\evil", "https://evil", "/x\nSet-Cookie:a",
                "/bff/login", "/BFF/logout", "/oauth2/authorization/x", "/login/oauth2/code/x", "/api/users",
                "/actuator/health"}) {
            assertThat(ReturnTo.sanitize(bad)).as(String.valueOf(bad)).isNull();
        }
    }

    private static OAuth2AuthenticationToken auth(Map<String, Object> idClaims) {
        Instant now = Instant.now();
        OidcIdToken id = new OidcIdToken("id", now, now.plusSeconds(60), idClaims);
        DefaultOidcUser user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), id, "sub");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "sso-admin-ui");
    }

    private static OAuth2AuthorizedClient client(String token) {
        ClientRegistration reg = ClientRegistration.withRegistrationId("sso-admin-ui").clientId("sso-admin-ui")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/x").authorizationUri("http://a/authorize").tokenUri("http://a/token").build();
        Instant now = Instant.now();
        return new OAuth2AuthorizedClient(reg, "sub",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token, now, now.plusSeconds(60)));
    }

    @Test
    void sessionInfoReadsRealmRolesFromTheAccessToken() {
        String token = BffTestSupport.jwt(Map.of("preferred_username", "u", "given_name", "Ada", "family_name",
                "Lovelace", "realm_access", Map.of("roles", List.of("system_admin", "inst:rra:auditor", "inst:minagri:hr",
                        "inst::broken", "inst:only"))));
        SessionInfo info = new SessionInfoService().describe(auth(Map.of("sub", "s")), client(token));
        assertThat(info.platformAdmin()).isTrue();
        assertThat(info.platformRoles()).containsExactly("system_admin");
        assertThat(info.institutions()).containsExactly("minagri", "rra");
        assertThat(info.institution()).isEqualTo("minagri");
        assertThat(info.name()).isEqualTo("Ada Lovelace");
        assertThat(info.landing()).isEqualTo("/dashboard");
    }

    @Test
    void anOpaqueAccessTokenFallsBackToIdTokenClaims() {
        SessionInfo info = new SessionInfoService().describe(
                auth(Map.of("sub", "s", "preferred_username", "fromid")), client("opaque-token"));
        assertThat(info.username()).isEqualTo("fromid");
        assertThat(info.realmRoles()).isEmpty();
        assertThat(info.landing()).isEqualTo("/no-access");
    }
}
