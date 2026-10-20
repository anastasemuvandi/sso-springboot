package rw.gov.risa.sso.mfa.policy;

/** Why the bulk CONFIGURE_TOTP job leaves a user alone ({@link MfaPolicy#bulkEnrolmentSkip}). */
public enum BulkSkipReason {
    DISABLED("disabled"),
    SERVICE_ACCOUNT("service_account"),
    /**
     * A pending CONFIGURE_TOTP is enforced by Keycloak whatever the flow says, so assigning
     * it to an exempt person would quietly revoke the exemption.
     */
    EXEMPT("exempt"),
    ALREADY_ENROLLED("already_enrolled"),
    ALREADY_PENDING("already_pending");

    private final String code;

    BulkSkipReason(String code) {
        this.code = code;
    }

    /** Wire/checkpoint code, identical to the reference ({@code "already_enrolled"}, ...). */
    public String code() {
        return code;
    }
}
