package rw.gov.risa.sso.wrapper.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Core settings shared by every feature. Feature-specific settings live in their own
 * {@code @ConfigurationProperties} classes next to the feature (see application.yml, where
 * each block maps the reference's environment variable names onto properties).
 *
 * @param debug              DEBUG: dev affordances (e.g. EMPLOYEE_PROVISION_DEV_PASSWORD) are honoured only when true
 * @param environment        APP_ENV: dev | staging | prod. staging/prod refuse to start with a missing secret
 * @param apiBaseUrl         API_BASE_URL: the wrapper's own public base URL (links in emails, discovery)
 * @param keycloak           Keycloak connection
 * @param cors               CORS allow-list
 */
@ConfigurationProperties(prefix = "sso")
public record SsoProperties(
        @DefaultValue("false") boolean debug,
        @DefaultValue("dev") String environment,
        @DefaultValue("http://localhost:8000") String apiBaseUrl,
        Keycloak keycloak,
        Cors cors) {

    public boolean isProductionLike() {
        return "staging".equalsIgnoreCase(environment) || "prod".equalsIgnoreCase(environment)
                || "production".equalsIgnoreCase(environment);
    }

    /**
     * @param serverUrl      KEYCLOAK_SERVER_URL: how the wrapper reaches Keycloak (docker hostname)
     * @param publicUrl      KEYCLOAK_PUBLIC_URL: browser-facing Keycloak base (authorize redirects)
     * @param localIssuer    KEYCLOAK_LOCAL_ISSUER: base of the `iss` claim Keycloak mints
     * @param realm          KEYCLOAK_REALM: the managed realm (default government-internal)
     * @param clientId       KEYCLOAK_CLIENT_ID: the wrapper's service-account client (sso-client)
     * @param clientSecret   KEYCLOAK_CLIENT_SECRET
     * @param exemptClients  ACCESS_ENFORCEMENT_EXEMPT_CLIENTS: control-plane + built-in clients
     */
    public record Keycloak(
            @DefaultValue("http://keycloak:8080") String serverUrl,
            @DefaultValue("http://localhost:8080") String publicUrl,
            @DefaultValue("http://localhost:8080") String localIssuer,
            @DefaultValue("government-internal") String realm,
            @DefaultValue("sso-client") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue({"sso-client", "sso-admin-ui", "account", "account-console", "admin-cli", "broker",
                    "realm-management", "security-admin-console"}) List<String> exemptClients) {

        public String issuer() {
            return trimSlash(localIssuer) + "/realms/" + realm;
        }

        public String internalRealmUrl() {
            return trimSlash(serverUrl) + "/realms/" + realm;
        }

        public String publicRealmUrl() {
            return trimSlash(publicUrl) + "/realms/" + realm;
        }

        private static String trimSlash(String s) {
            return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
        }
    }

    /** @param allowedOrigins ALLOWED_ORIGINS: explicit list; startup fails if it contains '*'. */
    public record Cors(@DefaultValue({}) List<String> allowedOrigins) {}
}
