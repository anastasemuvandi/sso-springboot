package rw.gov.risa.sso.bff.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * BFF configuration (see {@code application.yml} for the environment variable behind each one).
 *
 * @param wrapperUrl            wrapper base URL for server-side calls (token, jwks, /api relay)
 * @param wrapperPublicUrl      browser-facing wrapper base URL (authorize, logout); defaults to wrapperUrl
 * @param clientId              OIDC client id (confidential)
 * @param clientSecret          OIDC client secret (from env or {@code ADMIN_UI_CLIENT_SECRET_FILE})
 * @param redirectUri           callback URI registered on the client
 * @param postLogoutRedirectUri where the wrapper/Keycloak sends the browser after logout; blank = "{origin}/"
 * @param scopes                requested scopes
 * @param issuerUri             optional: pin the id_token {@code iss} (e.g. KEYCLOAK_LOCAL_ISSUER/realms/x)
 * @param jwkSetUri             JWKS for id_token signatures; blank = wrapperUrl + /oauth2/jwks
 * @param userInfoUri           optional userinfo endpoint (blank = not called)
 * @param discovery             read endpoints from the wrapper's discovery document at startup
 * @param refreshSkew           refresh this long before the access token expires
 * @param spaDir                directory with the built SPA (console-web/dist); blank = classpath:/static/ only
 * @param contentSecurityPolicy CSP header; blank = the built-in Vite-SPA policy
 * @param cookieSecure          Secure attribute on the session and XSRF cookies
 */
@ConfigurationProperties("bff")
public record BffProperties(
        @DefaultValue("http://localhost:8000") String wrapperUrl,
        String wrapperPublicUrl,
        @DefaultValue("sso-admin-ui") String clientId,
        String clientSecret,
        @DefaultValue("{baseUrl}/login/oauth2/code/{registrationId}") String redirectUri,
        String postLogoutRedirectUri,
        @DefaultValue({"openid", "profile", "email"}) List<String> scopes,
        String issuerUri,
        String jwkSetUri,
        String userInfoUri,
        @DefaultValue("false") boolean discovery,
        @DefaultValue("30s") Duration refreshSkew,
        String spaDir,
        String contentSecurityPolicy,
        @DefaultValue("true") boolean cookieSecure) {

    /** The registration id: login starts at /oauth2/authorization/sso-admin-ui. */
    public static final String REGISTRATION_ID = "sso-admin-ui";

    public String internalBase() {
        return stripSlash(wrapperUrl);
    }

    public String publicBase() {
        return stripSlash(wrapperPublicUrl == null || wrapperPublicUrl.isBlank() ? wrapperUrl : wrapperPublicUrl);
    }

    public String effectiveJwkSetUri() {
        return jwkSetUri == null || jwkSetUri.isBlank() ? internalBase() + "/oauth2/jwks" : jwkSetUri;
    }

    public boolean hasSecret() {
        return clientSecret != null && !clientSecret.isBlank();
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
