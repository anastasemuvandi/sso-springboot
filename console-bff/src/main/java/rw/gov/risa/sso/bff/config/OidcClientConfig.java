package rw.gov.risa.sso.bff.config;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.web.client.RestClient;

/**
 * The single OIDC client registration ({@code sso-admin-ui}) pointed at the WRAPPER's gateway, and
 * the authorized-client plumbing that keeps the token set in the HTTP session (hence in Redis).
 *
 * <p>Endpoints: authorize = PUBLIC wrapper {@code /oauth2/authorize} (the browser goes there); token
 * and JWKS = INTERNAL wrapper {@code /oauth2/token}, {@code /oauth2/jwks} (server-side calls); logout
 * = PUBLIC wrapper {@code /oauth2/logout}. With {@code BFF_OIDC_DISCOVERY=true} they are read from the
 * wrapper's {@code /oauth2/.well-known/openid-configuration} at startup instead (server-side ones are
 * rewritten from the public to the internal base URL). The discovered {@code issuer} is deliberately
 * NOT used: the wrapper advertises Keycloak's internal URL while tokens carry the public issuer; pin it
 * with {@code BFF_OIDC_ISSUER} instead.
 *
 * <p>Client authentication is {@code client_secret_post} (secret in the form body), like the reference
 * console. PKCE S256 is added to the authorization request by {@link SecurityConfig}.
 */
@Configuration
public class OidcClientConfig {
    private static final Logger log = LoggerFactory.getLogger(OidcClientConfig.class);

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(BffProperties p, Environment env,
            RestClient.Builder http) {
        boolean strict = Arrays.stream(env.getActiveProfiles()).anyMatch(s -> s.equals("staging") || s.equals("prod"));
        if (!p.hasSecret()) {
            if (strict) {
                throw new IllegalStateException(
                        "ADMIN_UI_CLIENT_SECRET (or ADMIN_UI_CLIENT_SECRET_FILE) is required in staging/prod");
            }
            log.warn("bff: ADMIN_UI_CLIENT_SECRET is not set; the wrapper will refuse the code exchange");
        }

        String authorizationUri = p.publicBase() + "/oauth2/authorize";
        String tokenUri = p.internalBase() + "/oauth2/token";
        String jwkSetUri = p.effectiveJwkSetUri();
        String userInfoUri = blankToNull(p.userInfoUri());
        String endSession = p.publicBase() + "/oauth2/logout";

        if (p.discovery()) {
            Map<String, Object> doc = http.build().get()
                    .uri(p.internalBase() + "/oauth2/.well-known/openid-configuration")
                    .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() { });
            if (doc == null) {
                throw new IllegalStateException("empty discovery document from the wrapper");
            }
            authorizationUri = str(doc, "authorization_endpoint", authorizationUri);
            tokenUri = internal(p, str(doc, "token_endpoint", tokenUri));
            jwkSetUri = blankToNull(p.jwkSetUri()) != null ? p.jwkSetUri() : internal(p, str(doc, "jwks_uri", jwkSetUri));
            endSession = str(doc, "end_session_endpoint", endSession);
            log.info("bff: OIDC endpoints read from the wrapper's discovery document");
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("end_session_endpoint", endSession);

        ClientRegistration.Builder b = ClientRegistration.withRegistrationId(BffProperties.REGISTRATION_ID)
                .clientId(p.clientId())
                .clientSecret(p.hasSecret() ? p.clientSecret() : "")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(p.redirectUri())
                .scope(p.scopes())
                .authorizationUri(authorizationUri)
                .tokenUri(tokenUri)
                .jwkSetUri(jwkSetUri)
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .providerConfigurationMetadata(metadata)
                .clientName("GoRSSO console");
        if (userInfoUri != null) {
            b.userInfoUri(userInfoUri);
        }
        if (blankToNull(p.issuerUri()) != null) {
            b.issuerUri(p.issuerUri());
        }
        return new InMemoryClientRegistrationRepository(b.build());
    }

    /** Tokens live in the HTTP session — i.e. in Redis — never in a cookie and never in memory only. */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /**
     * Used by TokenRelay and /bff/session: returns the caller's authorized client, refreshing it
     * (refresh_token grant at the wrapper, which re-runs its gates) when the access token is within
     * {@code bff.refresh-skew} of expiry. No client_credentials: the BFF has no identity of its own.
     */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository authorizedClients, BffProperties p) {
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken(r -> r.clockSkew(p.refreshSkew()))
                .build();
        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    private static String internal(BffProperties p, String url) {
        return url.startsWith(p.publicBase()) ? p.internalBase() + url.substring(p.publicBase().length()) : url;
    }

    private static String str(Map<String, Object> doc, String key, String fallback) {
        Object v = doc.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
