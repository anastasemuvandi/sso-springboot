package rw.gov.risa.sso.mfa;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JSON bodies POSTed to the audit service's {@code /audit/events}. Pure (no Keycloak
 * types) so the exact payload — identical to the reference gorsso-mfa listener — is
 * unit-tested. Keys are snake_case, in the reference order:
 * {@code event_type, user_id, app_id, realm, performed_by, target, ip_address, details, severity}.
 */
public final class AuditPayloads {

    private AuditPayloads() {
    }

    public static final String RECOVERY_CODE_USED = "auth.mfa.recovery_code_used";
    public static final String EXEMPT_ROLE_MAPPING = "admin.mfa.exempt_role_mapping";
    public static final String SWITCH_OBSERVED = "admin.mfa.switch_observed";
    public static final String SWITCH_OBSERVED_NOTE =
            "observed by Keycloak; a change made through the wrapper is also audited there with its actor";

    /** Envelope shared by every event. */
    public static Map<String, Object> envelope(String eventType, String userId, String appId, String realm,
                                               String performedBy, String target, String ipAddress,
                                               Map<String, Object> details, String severity) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", eventType);
        payload.put("user_id", userId);
        payload.put("app_id", appId);
        payload.put("realm", realm);
        payload.put("performed_by", performedBy);
        payload.put("target", target);
        payload.put("ip_address", ipAddress);
        payload.put("details", details);
        payload.put("severity", severity);
        return payload;
    }

    /**
     * A forwarded user event. The person is actor and target; {@code failedCredentialType}
     * is set only for a LOGIN_ERROR whose selected credential could be resolved.
     */
    public static Map<String, Object> userEvent(String auditType, String keycloakType, String username,
                                                String keycloakUserId, String sessionId, String error,
                                                String failedCredentialType, Map<String, String> keycloakDetails,
                                                String clientId, String realm, String ipAddress) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source", "keycloak");
        details.put("keycloak_event_type", keycloakType);
        details.put("keycloak_user_id", keycloakUserId);
        details.put("session", sessionId);
        if (error != null) {
            details.put("error", error);
        }
        if (failedCredentialType != null) {
            details.put("failed_credential_type", failedCredentialType);
        }
        details.put("keycloak_details", keycloakDetails == null ? Map.of() : keycloakDetails);
        return envelope(auditType, username, clientId, realm, username, username, ipAddress, details,
                AuditMapping.severity(auditType));
    }

    /** Details common to admin events: who did it, from which client/realm. */
    public static Map<String, Object> adminDetails(String authUserId, String authClientId, String authRealmId,
                                                   boolean hasAuthDetails) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source", "keycloak-admin");
        if (hasAuthDetails) {
            details.put("auth_user_id", authUserId);
            details.put("auth_client_id", authClientId);
            details.put("auth_realm_id", authRealmId);
        }
        return details;
    }

    /** Admin events: actor = the admin's user id, app = the admin's client. */
    public static Map<String, Object> admin(String type, String actorUserId, String actorClientId, String realm,
                                            String target, String ipAddress, Map<String, Object> details,
                                            String severity) {
        return envelope(type, actorUserId, actorClientId, realm, actorUserId, target, ipAddress, details, severity);
    }

    /** Posted by the re-enrol authenticator right after a recovery code was accepted. */
    public static Map<String, Object> recoveryCodeUsed(String username, String keycloakUserId, String clientId,
                                                       String realm, String ipAddress, boolean otpRemoved) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source", "keycloak");
        details.put("keycloak_user_id", keycloakUserId);
        details.put("otp_credentials_removed", otpRemoved);
        details.put("reenrolment_required", true);
        return envelope(RECOVERY_CODE_USED, username, clientId, realm, username, username, ipAddress, details,
                AuditMapping.severity(RECOVERY_CODE_USED));
    }
}
