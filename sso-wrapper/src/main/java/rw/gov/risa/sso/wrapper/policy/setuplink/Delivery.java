package rw.gov.risa.sso.wrapper.policy.setuplink;

/**
 * How the last set-password email to an account went ({@code sent} | {@code failed} |
 * {@code not_configured}), who sent it ({@code provisioning} | {@code holder_resend} |
 * {@code admin_resend}) and when (ISO-8601 UTC).
 */
public record Delivery(String status, String via, String at) {
}
