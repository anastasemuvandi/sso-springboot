package rw.gov.risa.sso.wrapper.audit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param serviceUrl AUDIT_SERVICE_URL
 * @param apiKey     AUDIT_API_KEY (header X-Audit-API-Key); held server-side, never sent to a browser
 * @param timeout    per-attempt timeout for the write path
 */
@ConfigurationProperties(prefix = "sso.audit")
public record AuditProperties(
        @DefaultValue("http://audit-service:8001") String serviceUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("2s") Duration timeout) {

    public static final String API_KEY_HEADER = "X-Audit-API-Key";
}
