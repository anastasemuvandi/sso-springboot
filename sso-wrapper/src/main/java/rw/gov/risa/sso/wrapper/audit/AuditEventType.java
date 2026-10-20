package rw.gov.risa.sso.wrapper.audit;

/**
 * Wrapper event names and the audit-service {@code event_type} each is stored under.
 * Unmapped events are stored under their own name. Add new events here.
 */
public enum AuditEventType {
    // Auth / OAuth2
    LOGIN_SUCCESS("login_success", "auth.login.success"),
    /**
     * Stored as {@code auth.gateway.login_failed}, NOT {@code auth.login.failed}: Keycloak's own audit
     * listener already records every credential failure as {@code auth.login.failed} (with the end
     * user's IP, which the gateway forwards), and the notification detector counts that type. Using the
     * same type here counted each failed password login twice.
     */
    LOGIN_FAILURE("login_failure", "auth.gateway.login_failed"),
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
    LIFECYCLE_OFFBOARD("lifecycle.offboard"),
    LIFECYCLE_REASSIGN("lifecycle.reassign"),
    LIFECYCLE_REHIRE("lifecycle.rehire"),
    PROVISION_USER("provision.user"),
    // IPPIS
    SYNC_ATTRIBUTES("ippis.sync.attributes"),
    SYNC_UNMAPPED_INSTITUTION("ippis.sync.unmapped_institution"),
    IPPIS_WEBHOOK("ippis.webhook.event"),
    // Admin — users
    ADMIN_USER_CREATED("admin.user.created"),
    ADMIN_USER_UPDATED("admin.user.updated"),
    ADMIN_USER_DISABLED("admin.user.disabled"),
    ADMIN_USER_DELETED("admin.user.deleted"),
    ADMIN_USER_PASSWORD_RESET("admin.user.password_reset"),
    // Admin — clients
    ADMIN_CLIENT_CREATED("admin.client.created"),
    ADMIN_CLIENT_UPDATED("admin.client.updated"),
    ADMIN_CLIENT_DELETED("admin.client.deleted"),
    ADMIN_CLIENT_SECRET_REGENERATED("admin.client.secret_regenerated"),
    ADMIN_CLIENT_SCOPE_CHANGED("admin.client.scope_changed"),
    ADMIN_CLIENT_JOIN_COLUMN_CHANGED("admin.client.join_column_changed"),
    // Admin — groups / agencies
    ADMIN_GROUP_CREATED("admin.group.created"),
    ADMIN_GROUP_UPDATED("admin.group.updated"),
    ADMIN_GROUP_DELETED("admin.group.deleted"),
    ADMIN_GROUP_MEMBER_ADDED("admin.group.member_added"),
    ADMIN_GROUP_MEMBER_REMOVED("admin.group.member_removed"),
    // Admin — roles
    ADMIN_ROLE_CREATED("admin.role.created"),
    ADMIN_ROLE_UPDATED("admin.role.updated"),
    ADMIN_ROLE_DELETED("admin.role.deleted"),
    ADMIN_ROLE_ASSIGNED("admin.role.assigned"),
    ADMIN_ROLE_REMOVED("admin.role.removed"),
    // Admin — realm
    ADMIN_REALM_UPDATED("admin.realm.updated"),
    // Per-client access
    ACCESS_GRANTED("admin.access.granted"),
    ACCESS_REVOKED("admin.access.revoked"),
    ACCESS_POLICY_UPDATED("admin.access.policy_updated"),
    // Delegated permissions
    DELEGATED_APPLIED("admin.delegated.applied"),
    DELEGATED_GUARDRAIL_VIOLATION("admin.delegated.guardrail_violation"),
    // Sessions
    SESSION_TERMINATED("session.terminated"),
    // MFA
    MFA_SWITCH_CHANGED("admin.mfa.switch_changed"),
    MFA_EXEMPT_GRANTED("admin.mfa.exempt_granted"),
    MFA_EXEMPT_REVOKED("admin.mfa.exempt_revoked"),
    MFA_BULK_ENROL("admin.mfa.bulk_enrol"),
    MFA_CLIENT_REQUIREMENT_CHANGED("admin.mfa.client_requirement_changed"),
    MFA_PASSWORD_GRANT_REFUSED("auth.mfa.password_grant_refused"),
    // Employees / setup links / access matrix
    EMPLOYEE_NID_REVEALED("admin.employee.nid_revealed"),
    SETUP_LINK_RESEND("auth.setup_link.resend"),
    SETUP_LINK_USED("auth.setup_link.used"),
    SETUP_LINK_ADMIN_RESEND("admin.setup_link.resend"),
    ACCESS_MATRIX_EXPORTED("admin.access_matrix.exported"),
    ACCESS_MATRIX_IMPORTED("admin.access_matrix.imported");

    private final String value;
    private final String auditServiceType;

    AuditEventType(String value) {
        this(value, value);
    }

    AuditEventType(String value, String auditServiceType) {
        this.value = value;
        this.auditServiceType = auditServiceType;
    }

    /** The wrapper's own name (structured logs). */
    public String value() {
        return value;
    }

    /** The audit-service {@code event_type}. */
    public String auditServiceType() {
        return auditServiceType;
    }
}
