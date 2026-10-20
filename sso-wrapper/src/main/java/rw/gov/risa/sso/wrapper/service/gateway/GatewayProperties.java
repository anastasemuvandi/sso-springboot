package rw.gov.risa.sso.wrapper.service.gateway;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The auth / OIDC gateway ({@code sso.gateway.*}, spec §5.1).
 *
 * @param enableSaml              ENABLE_SAML: {@code GET /saml/metadata} answers 404 while false (reference default)
 * @param codeTtl                 AUTH_CODE_TTL: lifetime of a wrapper-issued authorization code (300 s)
 * @param httpTimeout             GATEWAY_HTTP_TIMEOUT: per-call timeout towards Keycloak's OIDC endpoints
 * @param allowWildcardRedirects  REDIRECT_URI_ALLOW_WILDCARDS: honour a registered URI ending in {@code *} as a
 *                                prefix (Keycloak's own rule). Off by default: the spec asks for an EXACT match.
 */
@ConfigurationProperties(prefix = "sso.gateway")
public record GatewayProperties(
        @DefaultValue("false") boolean enableSaml,
        @DefaultValue("300s") Duration codeTtl,
        @DefaultValue("10s") Duration httpTimeout,
        @DefaultValue("false") boolean allowWildcardRedirects) {
}
