package rw.gov.risa.sso.wrapper.policy.overview;

/**
 * One account waiting for its first password. {@code setupEmail} is {@code sent} | {@code failed} |
 * {@code not_configured} | {@code unknown}; {@code resendable} = an admin may send one now.
 */
public record PasswordSetupRow(
        String sub, String username, String email, boolean enabled, String employeeNumber,
        String setupEmail, String at, boolean resendable) {
}
