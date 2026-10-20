package rw.gov.risa.sso.wrapper.policy.audit;

import java.util.Optional;

/**
 * Wrapper audit event types (port of {@code core/audit.py EventType}) with their exact wire
 * strings, and the audit-service {@code event_type} each maps to ({@code _AUDIT_EVENT_MAP};
 * unmapped types pass through unchanged).
 */
public enum AuditEventType {
    // Auth / OAuth2
    LOGIN_SUCCESS("login_success", "auth.login.success"),
    LOGIN_FAILURE("login_failure", "auth.login.failed"),
    LOGIN_DENIED("login_denied", "authz.access.denied"),
    LOGOUT("logout", "auth.logout"),
    TOKEN_VERIFY("token_verify", "authz.token.validated"),
    TOKEN_ISSUED("token_issued", "authz.token.validated"),
    TOKEN_REFRESHED("token_refreshed", "session.refreshed"),
    TOKEN_REJECTED("token_rejected", "authz.token.invalid"),
    AUTHORIZE_START("authorize_start", "auth.session.created"),
    AUTHORIZE_REDIRECT_DENIED("authorize_redirect_denied", "authz.access.denied"),
    AUTHORIZE_INTERNAL_CODE("authorize_internal_code", "auth.session.created"),
    LOGOUT_REDIRECT_DENIED("logout_redirect_denied", "authz.access.denied"),
    // Lifecycle
    LIFECYCLE_OFFBOARD("lifecycle.offboard", "lifecycle.offboard"),
    LIFECYCLE_REASSIGN("lifecycle.reassign", "lifecycle.reassign"),
    /** Spec addition: an automatic re-enable under the re-hire rule. */
    LIFECYCLE_REHIRE("lifecycle.rehire", null),
    // Provisioning
    PROVISION_USER("provision.user", "provision.user"),
    // IPPIS attribute sourcing
    SYNC_ATTRIBUTES("ippis.sync.attributes", "ippis.sync.attributes"),
    SYNC_UNMAPPED_INSTITUTION("ippis.sync.unmapped_institution", "ippis.sync.unmapped_institution"),
    // Admin — users
    ADMIN_USER_CREATED("admin.user.created", null),
    ADMIN_USER_UPDATED("admin.user.updated", null),
    ADMIN_USER_DISABLED("admin.user.disabled", null),
    ADMIN_USER_DELETED("admin.user.deleted", null),
    // Admin — clients
    ADMIN_CLIENT_CREATED("admin.client.created", null),
    ADMIN_CLIENT_UPDATED("admin.client.updated", null),
    ADMIN_CLIENT_DELETED("admin.client.deleted", null),
    // Admin — groups / agencies
    ADMIN_GROUP_CREATED("admin.group.created", null),
    ADMIN_GROUP_UPDATED("admin.group.updated", null),
    ADMIN_GROUP_DELETED("admin.group.deleted", null),
    ADMIN_GROUP_MEMBER_ADDED("admin.group.member_added", null),
    ADMIN_GROUP_MEMBER_REMOVED("admin.group.member_removed", null),
    // Admin — roles
    ADMIN_ROLE_CREATED("admin.role.created", null),
    ADMIN_ROLE_ASSIGNED("admin.role.assigned", null),
    ADMIN_ROLE_REMOVED("admin.role.removed", null),
    // Sessions
    SESSION_TERMINATED("session.terminated", null),
    // MFA
    MFA_SWITCH_CHANGED("admin.mfa.switch_changed", null),
    MFA_EXEMPT_GRANTED("admin.mfa.exempt_granted", null),
    MFA_EXEMPT_REVOKED("admin.mfa.exempt_revoked", null),
    MFA_BULK_ENROL("admin.mfa.bulk_enrol", null),
    MFA_CLIENT_REQUIREMENT_CHANGED("admin.mfa.client_requirement_changed", null),
    EMPLOYEE_NID_REVEALED("admin.employee.nid_revealed", null),
    MFA_PASSWORD_GRANT_REFUSED("auth.mfa.password_grant_refused", null),
    // Setup link
    SETUP_LINK_RESEND("auth.setup_link.resend", null),
    SETUP_LINK_USED("auth.setup_link.used", null),
    SETUP_LINK_ADMIN_RESEND("admin.setup_link.resend", null),
    // Access matrix
    ACCESS_MATRIX_EXPORTED("admin.access_matrix.exported", null),
    ACCESS_MATRIX_IMPORTED("admin.access_matrix.imported", null);

    private final String wire;
    private final String serviceType;

    AuditEventType(String wire, String serviceType) {
        this.wire = wire;
        this.serviceType = serviceType;
    }

    /** The wrapper's own event string ({@code EventType.value}). */
    public String wire() {
        return wire;
    }

    /** The audit-service {@code event_type}: mapped where the reference maps it, else {@link #wire()}. */
    public String auditServiceType() {
        return serviceType != null ? serviceType : wire;
    }

    public static Optional<AuditEventType> fromWire(String value) {
        for (AuditEventType t : values()) {
            if (t.wire.equals(value)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
