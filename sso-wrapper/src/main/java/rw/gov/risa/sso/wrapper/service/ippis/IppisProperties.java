package rw.gov.risa.sso.wrapper.service.ippis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * IPPIS sourcing, reconcile, webhook and leaver settings ({@code sso.ippis.*}). Every field maps a
 * reference environment variable (see application.yml).
 *
 * @param baseUrl                 IPPIS_BASE_URL (must be https in production — the hop carries national ids)
 * @param apiKey                  IPPIS_API_KEY (auth method api_key, header X-IPPIS-API-Key)
 * @param timeout                 IPPIS_SYNC_TIMEOUT — connect + read timeout of one IPPIS call
 * @param employeeNumberAttribute IPPIS_EMPLOYEE_NUMBER_ATTRIBUTE — the Keycloak join attribute
 * @param concurrency             IPPIS_SYNC_CONCURRENCY — users processed in parallel
 * @param maxAttempts             IPPIS_SYNC_MAX_ATTEMPTS — total tries for a transient IPPIS read / Keycloak write
 * @param retryBackoff            IPPIS_SYNC_RETRY_BACKOFF — linear backoff base (attempt n waits n x this)
 * @param pageSize                IPPIS_SYNC_PAGE_SIZE — Keycloak user page size on a full pass
 * @param clearMissing            IPPIS_CLEAR_MISSING — clear managed attributes IPPIS stopped providing
 * @param watermarkKey            IPPIS_SYNC_WATERMARK_KEY — Redis key of the delta watermark
 */
@ConfigurationProperties(prefix = "sso.ippis")
public record IppisProperties(
        @DefaultValue("http://ippis-mock:8002") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("5s") Duration timeout,
        @DefaultValue("employee_number") String employeeNumberAttribute,
        @DefaultValue("10") int concurrency,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("500ms") Duration retryBackoff,
        @DefaultValue("100") int pageSize,
        @DefaultValue("false") boolean clearMissing,
        @DefaultValue("gorsso:ippis:sync:watermark") String watermarkKey,
        @DefaultValue Auth auth,
        @DefaultValue Webhook webhook,
        @DefaultValue Schedule schedule) {

    /**
     * IPPIS_AUTH_METHOD = api_key | oauth2 | mtls (+ IPPIS_OAUTH_*, IPPIS_MTLS_CERT/KEY,
     * IPPIS_CA_BUNDLE). Certificates and keys are a path or PEM content.
     */
    public record Auth(
            @DefaultValue("api_key") String method,
            @DefaultValue("") String oauthTokenUrl,
            @DefaultValue("") String oauthClientId,
            @DefaultValue("") String oauthClientSecret,
            @DefaultValue("") String oauthScope,
            @DefaultValue("") String mtlsCert,
            @DefaultValue("") String mtlsKey,
            @DefaultValue("") String caBundle) {
    }

    /**
     * @param secret  IPPIS_WEBHOOK_SECRET — empty = POST /ippis/events answers 503
     * @param maxSkew IPPIS_WEBHOOK_MAX_SKEW — accepted |now - X-IPPIS-Timestamp|
     */
    public record Webhook(
            @DefaultValue("") String secret,
            @DefaultValue("300s") Duration maxSkew,
            @DefaultValue("gorsso:ippis:webhook:nonce:") String noncePrefix) {

        public boolean enabled() {
            return secret != null && !secret.isBlank();
        }

        /** Nonces outlive the whole acceptance window on both sides of now. */
        public Duration nonceTtl() {
            return maxSkew.multipliedBy(2).plusSeconds(60);
        }
    }

    /**
     * @param enabled   IPPIS_SYNC_SCHEDULE_ENABLED — run delta/full in-process (ShedLock across instances)
     * @param deltaCron IPPIS_SYNC_DELTA_CRON
     * @param fullCron  IPPIS_SYNC_FULL_CRON
     */
    public record Schedule(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("0 */5 * * * *") String deltaCron,
            @DefaultValue("0 30 2 * * *") String fullCron) {
    }

    public int effectiveConcurrency() {
        return Math.max(1, concurrency);
    }

    public int effectiveMaxAttempts() {
        return Math.max(1, maxAttempts);
    }
}
