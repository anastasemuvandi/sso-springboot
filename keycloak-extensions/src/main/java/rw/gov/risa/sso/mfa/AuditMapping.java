package rw.gov.risa.sso.mfa;

import rw.gov.risa.sso.mfa.policy.MfaConstants;

import java.util.Map;

/**
 * Which Keycloak events reach the audit service, and under what name.
 *
 * <p>Pure — no Keycloak types — so the policy is unit-tested without a server.
 * {@code null} means "not forwarded": Keycloak's own event store still records
 * everything in {@code enabled_event_types}; this is only what the hash-chained
 * trail receives. Names use the {@code auth.} / {@code admin.} prefixes the
 * audit service derives its categories from.
 */
public final class AuditMapping {

    private AuditMapping() {
    }

    public static final String OTP = MfaConstants.OTP_CREDENTIAL_TYPE;
    public static final String RECOVERY_CODES = MfaConstants.RECOVERY_CODES_CREDENTIAL_TYPE;

    /**
     * @param type               Keycloak {@code EventType} name
     * @param details            the event's details map (may be null)
     * @param selectedCredential type of the credential named by the event's
     *                           {@code selected_credential_id}, resolved by the
     *                           listener ({@code null} when there is none)
     */
    public static String userEvent(String type, Map<String, String> details, String selectedCredential) {
        Map<String, String> d = details == null ? Map.of() : details;
        String credentialType = d.get("credential_type");
        switch (type) {
            case "LOGIN_ERROR":
                // A failed OTP or recovery code does NOT carry anything that
                // says "second factor" — Keycloak posts straight to the form's
                // execution without re-running the flow's conditions. What it
                // does carry is `selected_credential_id`, the credential the
                // form checked, and its type says which factor failed.
                if (OTP.equals(selectedCredential)) {
                    return "auth.mfa.otp_failed";
                }
                if (RECOVERY_CODES.equals(selectedCredential)) {
                    return "auth.mfa.recovery_code_failed";
                }
                return "auth.login.failed";
            case "LOGIN":
                // Recovery-code use is posted directly by the re-enrol
                // authenticator: by the time LOGIN is sent, the required-action
                // phase has replaced the details that could have said so.
                return null;
            case "UPDATE_TOTP":
                return "auth.mfa.totp_enrolled";
            case "REMOVE_TOTP":
                return "auth.mfa.totp_removed";
            case "UPDATE_CREDENTIAL":
                // OTP is already covered by UPDATE_TOTP, which Keycloak 26 still
                // emits alongside; forwarding both would double every enrolment.
                return RECOVERY_CODES.equals(credentialType) ? "auth.mfa.recovery_codes_configured" : null;
            case "REMOVE_CREDENTIAL":
                return RECOVERY_CODES.equals(credentialType) ? "auth.mfa.recovery_codes_removed" : null;
            case "UPDATE_CREDENTIAL_ERROR":
                return OTP.equals(credentialType) || RECOVERY_CODES.equals(credentialType)
                        ? "auth.mfa.enrol_failed"
                        : null;
            default:
                return null;
        }
    }

    /**
     * Admin events worth forwarding, or null. A credential removed through the
     * Admin API (console, or a script) is an ADMIN event — Keycloak emits no
     * REMOVE_TOTP for it — so without this the one removal an attacker with
     * admin access would use leaves no trace in the trail.
     *
     * @param resourceType Keycloak {@code ResourceType} name
     * @param operation    Keycloak {@code OperationType} name
     * @param path         resource path, e.g. {@code users/<id>/credentials/<cid>}
     */
    public static String adminEvent(String resourceType, String operation, String path) {
        // 26.2.5 records the removal as ACTION, not DELETE (seen on the probe);
        // DELETE is accepted too in case a later version corrects it. The exact
        // path is what makes ACTION unambiguous: the other credential operations
        // live below it (…/userLabel, …/moveToFirst).
        if ("USER".equals(resourceType) && ("ACTION".equals(operation) || "DELETE".equals(operation))
                && path != null && path.matches("users/[^/]+/credentials/[^/]+")) {
            return "admin.mfa.credential_removed";
        }
        return null;
    }

    /**
     * A realm-role mapping change whose representation names {@code mfa-exempt}: granted or
     * removed outside the wrapper's exemption endpoint (or through it — both are recorded).
     *
     * @param resourceType   Keycloak {@code ResourceType} name
     * @param representation the admin event's JSON representation (may be null)
     */
    public static boolean namesExemptRole(String resourceType, String representation) {
        return "REALM_ROLE_MAPPING".equals(resourceType)
                && representation != null
                && representation.contains("\"" + MfaConstants.EXEMPT_ROLE + "\"");
    }

    /**
     * Whether a realm update changed the effective TOTP mode, given the mode this node saw
     * before ({@code null} = first sight since restart: prime, do not report) and now.
     */
    public static boolean switchChanged(String before, String now) {
        return before != null && !before.equals(now);
    }

    public static String severity(String auditType) {
        if (auditType == null) {
            return "INFO";
        }
        if (auditType.endsWith("_failed") || auditType.endsWith("_removed")
                || auditType.equals("auth.mfa.recovery_code_used")) {
            return "WARNING";
        }
        return "INFO";
    }
}
