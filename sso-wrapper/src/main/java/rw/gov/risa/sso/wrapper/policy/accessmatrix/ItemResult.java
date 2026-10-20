package rw.gov.risa.sso.wrapper.policy.accessmatrix;

/**
 * One per-item result of a bulk change. {@code reason} is a machine code (a
 * {@link RejectionReason#code()}, {@code keycloak_error}, or a provisioning reason), null when
 * applied. {@code sub} is the account's user id when known.
 */
public record ItemResult(
        String employeeNumber, String sub, String clientId, ItemStatus status, String reason, String message) {

    public static final String REASON_KEYCLOAK_ERROR = "keycloak_error";
}
