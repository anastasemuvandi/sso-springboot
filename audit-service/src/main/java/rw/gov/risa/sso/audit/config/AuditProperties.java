package rw.gov.risa.sso.audit.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration. {@code application.yml} maps the reference's environment variable names
 * ({@code AUDIT_API_KEY}, {@code KEYCLOAK_*}, {@code WRAPPER_URL}, ...) onto these properties.
 *
 * @param environment APP_ENV: dev | staging | prod. staging/prod refuse to start without real secrets.
 * @param apiKey      AUDIT_API_KEY (or AUDIT_API_KEY_FILE): the key callers send in X-Audit-API-Key.
 * @param apiKeys     AUDIT_API_KEYS (or _FILE): optional extra comma-separated keys, for rotation.
 */
@ConfigurationProperties(prefix = "audit")
public record AuditProperties(
        @DefaultValue("dev") String environment,
        String apiKey,
        String apiKeys,
        @DefaultValue Workers workers,
        @DefaultValue Health health,
        @DefaultValue Keycloak keycloak,
        @DefaultValue Retention retention) {

    public boolean isProductionLike() {
        return "staging".equalsIgnoreCase(environment) || "prod".equalsIgnoreCase(environment)
                || "production".equalsIgnoreCase(environment);
    }

    /** Every accepted API key (primary + rotation list), blanks removed. */
    public List<String> acceptedKeys() {
        List<String> keys = new ArrayList<>();
        if (apiKey != null && !apiKey.isBlank()) {
            keys.add(apiKey.strip());
        }
        if (apiKeys != null) {
            for (String k : apiKeys.split(",")) {
                if (!k.isBlank()) {
                    keys.add(k.strip());
                }
            }
        }
        return keys;
    }

    /** AUDIT_WORKERS_ENABLED: run the scheduled workers in this instance (ShedLock still elects one). */
    public record Workers(@DefaultValue("true") boolean enabled) {
    }

    /**
     * Health-check worker (reference SERVICES_TO_MONITOR / build_service_health_urls).
     *
     * @param services  names checked every run; "postgresql" and "redis" are probed natively,
     *                  the rest over HTTP using {@code urls}
     * @param urls      service name → health URL
     * @param redisUrl  REDIS_URL (redis://[:password@]host:port/db); blank → redis reported UNKNOWN
     */
    public record Health(
            @DefaultValue({"keycloak", "wrapper", "admin-ui", "postgresql", "redis"}) List<String> services,
            @DefaultValue Map<String, String> urls,
            String redisUrl,
            @DefaultValue("5s") Duration timeout) {
        public Health {
            urls = urls == null ? new LinkedHashMap<>() : new LinkedHashMap<>(urls);
        }
    }

    /** Certificate-expiry worker: reads the certExpiry user attribute through the Admin REST API. */
    public record Keycloak(
            @DefaultValue("http://keycloak:8080") String serverUrl,
            @DefaultValue("government-internal") String realm,
            @DefaultValue("admin") String adminUsername,
            String adminPassword,
            @DefaultValue("master") String adminRealm,
            @DefaultValue("admin-cli") String adminClientId) {
    }

    /**
     * @param serviceHealthDays how long health snapshots are kept (the reference kept them forever)
     */
    public record Retention(@DefaultValue("90") int serviceHealthDays) {
    }
}
