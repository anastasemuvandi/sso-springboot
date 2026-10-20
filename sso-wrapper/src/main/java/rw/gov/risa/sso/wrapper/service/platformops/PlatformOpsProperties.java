package rw.gov.risa.sso.wrapper.service.platformops;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Platform realm / dashboard / ops settings ({@code sso.platform-ops.*}; see application.yml).
 *
 * @param auditReadTimeout   AUDIT_READ_TIMEOUT: audit-service reads (events, verify, retention)
 * @param auditExportTimeout AUDIT_EXPORT_TIMEOUT: CSV/PDF exports (can walk tens of thousands of rows)
 * @param overviewCacheTtl   PLATFORM_OVERVIEW_CACHE_TTL: {@code GET /dashboard/overview} cache lifetime (60 s)
 * @param logoMaxBytes       REALM_LOGO_MAX_BYTES: {@code POST /realm/branding/logo} limit (5 MB, as the reference)
 */
@ConfigurationProperties(prefix = "sso.platform-ops")
public record PlatformOpsProperties(
        @DefaultValue("10s") Duration auditReadTimeout,
        @DefaultValue("60s") Duration auditExportTimeout,
        @DefaultValue("60s") Duration overviewCacheTtl,
        @DefaultValue("5242880") long logoMaxBytes) {
}
