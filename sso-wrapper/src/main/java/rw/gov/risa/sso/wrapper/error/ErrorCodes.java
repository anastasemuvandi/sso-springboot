package rw.gov.risa.sso.wrapper.error;

/** Machine codes used in the {@code error} field of the envelope. Add new ones here. */
public final class ErrorCodes {
    private ErrorCodes() {}

    public static final String UNAUTHORIZED = "unauthorized";
    public static final String INVALID_TOKEN = "invalid_token";
    public static final String FORBIDDEN = "forbidden";
    public static final String NOT_FOUND = "not_found";
    public static final String VALIDATION_ERROR = "validation_error";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String INTERNAL_ERROR = "internal_error";
    public static final String CONFLICT = "conflict";

    // Gates (spec §5.1)
    public static final String ACCESS_DENIED = "access_denied";
    public static final String MFA_REQUIRED = "mfa_required";
    public static final String JOIN_KEY_MISSING = "join_key_missing";
    public static final String JOIN_KEY_INVALID = "join_key_invalid";
    public static final String JOIN_KEY_NOT_UNIQUE = "join_key_not_unique";

    // OAuth2 (RFC 6749 §5.2 names, returned in the envelope's error field)
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String INVALID_CLIENT = "invalid_client";
    public static final String INVALID_GRANT = "invalid_grant";
    public static final String UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";

    // Provisioning / access matrix
    public static final String NOTHING_TO_ASSIGN = "nothing_to_assign";
    public static final String ACCOUNT_AMBIGUOUS = "account_ambiguous";
    public static final String ACCOUNT_OTHER_INSTITUTION = "account_other_institution";
    public static final String STATE_CHANGED = "state_changed";
    public static final String CONFIRM_REQUIRED = "confirm_required";

    // Infrastructure
    public static final String KEYCLOAK_ERROR = "keycloak_error";
    public static final String UPSTREAM_UNAVAILABLE = "upstream_unavailable";
    public static final String NOT_CONFIGURED = "not_configured";
    public static final String IDENTITY_DB_NOT_CONFIGURED = "identity_db_not_configured";
    public static final String DELEGATION_UNAVAILABLE = "delegation_unavailable";
}
