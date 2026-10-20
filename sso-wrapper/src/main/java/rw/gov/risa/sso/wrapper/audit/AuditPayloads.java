package rw.gov.risa.sso.wrapper.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the audit-service ingest payload ({@code POST /audit/events}). */
public final class AuditPayloads {
    private AuditPayloads() {}

    public static Map<String, Object> toPayload(AuditEvent e) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("outcome", e.outcome());
        detail.put("session", e.session());
        detail.putAll(e.extra());

        boolean bad = "failure".equals(e.outcome()) || "denied".equals(e.outcome());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", e.type().auditServiceType());
        payload.put("user_id", e.actor());
        payload.put("app_id", e.clientId());
        payload.put("performed_by", e.actor());
        payload.put("ip_address", e.requestIp());
        payload.put("institution", firstString(detail, "institution"));
        payload.put("target", firstString(detail, "target_user", "target", "target_client"));
        payload.put("details", detail);
        payload.put("severity", bad ? "WARNING" : "INFO");
        return payload;
    }

    static String firstString(Map<String, Object> detail, String... keys) {
        for (String k : keys) {
            if (detail.get(k) instanceof String s && !s.isBlank()) {
                return s.strip();
            }
        }
        return null;
    }
}
