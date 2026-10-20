package rw.gov.risa.sso.wrapper.policy.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the audit-service POST body exactly as {@code audit_event_async} does.
 * {@code institution} and {@code target} are first-class columns (covered by the v2 hash
 * chain) and are ALSO left in {@code details}. The {@code actor} must come from the
 * authenticated principal (invariant 7) — never from a request body.
 */
public final class AuditPayloads {

    private AuditPayloads() {
    }

    public static Map<String, Object> build(AuditEventType event, String outcome, String actor, String clientId,
                                            String session, String requestIp, Map<String, ?> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("outcome", outcome);
        detail.put("session", session);
        if (extra != null) {
            detail.putAll(extra);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", event.auditServiceType());
        payload.put("user_id", actor);
        payload.put("app_id", clientId);
        payload.put("performed_by", actor);
        payload.put("ip_address", requestIp);
        payload.put("institution", firstStr(detail, "institution"));
        payload.put("target", firstStr(detail, "target_user", "target", "target_client"));
        payload.put("details", detail);
        payload.put("severity", AuditOutcome.severityFor(outcome));
        return payload;
    }

    /** First non-blank string among {@code keys} in {@code detail}, stripped; else null. */
    public static String firstStr(Map<String, ?> detail, String... keys) {
        for (String k : keys) {
            Object v = detail.get(k);
            if (v instanceof String s && !s.isBlank()) {
                return s.strip();
            }
        }
        return null;
    }
}
