package rw.gov.risa.sso.wrapper.security;

import java.time.Duration;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.keycloak.KnownClientsCache;

/**
 * Verification policy for every bearer the wrapper trusts:
 * RS256 only (no alg confusion) against the realm JWKS (fetched over the internal URL),
 * issuer pinned to {@code KEYCLOAK_LOCAL_ISSUER/realms/<realm>}, exp/nbf with 5 s skew, and
 * {@code azp} must be a clientId registered in the realm.
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(SsoProperties props, KnownClientsCache knownClients) {
        SsoProperties.Keycloak kc = props.keycloak();
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(kc.internalRealmUrl() + "/protocol/openid-connect/certs")
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator(kc.issuer(), knownClients));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> validator(String issuer, KnownClientsCache knownClients) {
        return new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtTimestampValidator(Duration.ofSeconds(5)),
                new JwtIssuerValidator(issuer),
                azpValidator(knownClients)));
    }

    static OAuth2TokenValidator<Jwt> azpValidator(KnownClientsCache knownClients) {
        return jwt -> {
            String azp = jwt.getClaimAsString("azp");
            if (azp == null || azp.isBlank()) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token has no azp", null));
            }
            try {
                if (!knownClients.isKnown(azp)) {
                    return OAuth2TokenValidatorResult.failure(
                            new OAuth2Error("invalid_token", "azp is not a client of this realm", null));
                }
            } catch (RuntimeException e) {
                // Fail closed: if we cannot tell whether the client exists, the token is not trusted.
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Unable to verify azp", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
    }
}
