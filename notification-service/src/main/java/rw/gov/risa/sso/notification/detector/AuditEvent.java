package rw.gov.risa.sso.notification.detector;

import java.time.Instant;
import java.util.Objects;

/**
 * The slice of an audit-service event the detector needs (fields of the audit
 * service's {@code AuditEventRead}: {@code id, event_type, user_id, ip_address,
 * app_id, created_at}). Pure value; parsing from JSON happens in the audit client.
 */
public record AuditEvent(
        String id,
        String eventType,
        String userId,
        String ipAddress,
        String appId,
        Instant createdAt) {

    public AuditEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        userId = blankToNull(userId);
        ipAddress = blankToNull(ipAddress);
        appId = blankToNull(appId);
    }

    public boolean isLoginSuccess() {
        return EventTypes.LOGIN_SUCCESS.contains(eventType);
    }

    public boolean isLoginFailure() {
        return EventTypes.LOGIN_FAILED.contains(eventType);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
