package rw.gov.risa.sso.wrapper.service.institutionadmin;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Institution-admin surface settings. The audit-service URL and key are core
 * ({@code sso.audit.*}: AUDIT_SERVICE_URL, AUDIT_API_KEY).
 *
 * @param auditReadTimeout INSTITUTION_AUDIT_READ_TIMEOUT: per-request timeout of the scoped audit read
 */
@ConfigurationProperties(prefix = "sso.institution-admin")
public record InstitutionAdminProperties(@DefaultValue("10s") Duration auditReadTimeout) {
}
