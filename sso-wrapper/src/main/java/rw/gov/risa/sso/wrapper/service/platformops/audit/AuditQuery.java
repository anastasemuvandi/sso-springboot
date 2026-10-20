package rw.gov.risa.sso.wrapper.service.platformops.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Filters for the audit-service read endpoints, named as the audit-service names them
 * ({@code GET /audit/events}, {@code /audit/export/{fmt}}). Null = not sent. Dates are passed through
 * as given (the audit-service accepts ISO-8601 with an offset or {@code Z}, a naive datetime, or a date).
 */
public record AuditQuery(
        String eventType,
        String category,
        String userId,
        String appId,
        String realm,
        String institution,
        String target,
        String severity,
        String fromDate,
        String toDate,
        Integer limit,
        Integer offset) {

    public static AuditQuery empty() {
        return new AuditQuery(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** The same query with {@code institution} replaced (the institution-scoped view pins it). */
    public AuditQuery withInstitution(String slug) {
        return new AuditQuery(eventType, category, userId, appId, realm, slug, target, severity, fromDate, toDate,
                limit, offset);
    }

    /** Query parameters in the audit-service's snake_case names, nulls and blanks dropped. */
    public Map<String, String> params() {
        Map<String, String> m = new LinkedHashMap<>();
        put(m, "event_type", eventType);
        put(m, "category", category);
        put(m, "user_id", userId);
        put(m, "app_id", appId);
        put(m, "realm", realm);
        put(m, "institution", institution);
        put(m, "target", target);
        put(m, "severity", severity);
        put(m, "from_date", fromDate);
        put(m, "to_date", toDate);
        put(m, "limit", limit == null ? null : String.valueOf(limit));
        put(m, "offset", offset == null ? null : String.valueOf(offset));
        return m;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v.strip());
        }
    }
}
