package rw.gov.risa.sso.wrapper.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

class CallerTokenConverterTest {

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("t").header("alg", "RS256").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).subject("u1").claim("azp", "sso-admin-ui");
    }

    @Test
    void readsRolesAndInstitution() {
        CallerPrincipal p = CallerTokenConverter.toPrincipal(jwt()
                .claim("preferred_username", "minagri.admin")
                .claim("realm_access", Map.of("roles", List.of("inst:minagri:admin", "offline_access")))
                .claim("resource_access", Map.of("realm-management", Map.of("roles", List.of("manage-users"))))
                .claim("institution", List.of(" MINAGRI "))
                .build());
        assertThat(p.username()).isEqualTo("minagri.admin");
        assertThat(p.realmRoles()).containsExactlyInAnyOrder("inst:minagri:admin", "offline_access");
        assertThat(p.clientRoles("realm-management")).containsExactly("manage-users");
        assertThat(p.institutionClaim()).isEqualTo("minagri");
        assertThat(p.isPlatformAdmin()).isFalse();
    }

    @Test
    void tokenWithoutPreferredUsernameIsRejected() {
        assertThatThrownBy(() -> CallerTokenConverter.toPrincipal(jwt().build()))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void toStringNeverLeaksTheToken() {
        CallerPrincipal p = CallerTokenConverter.toPrincipal(jwt().claim("preferred_username", "x").build());
        assertThat(p.toString()).doesNotContain("t,").doesNotContain("accessToken");
    }
}
