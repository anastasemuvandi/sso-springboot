package rw.gov.risa.sso.wrapper.policy.overview;

/**
 * The whole overview. {@code institution} null = platform (realm-wide). {@code members} = accounts
 * in scope (null when the user walk failed). Each section is independent, with its own
 * available/error — never a false zero.
 */
public record OverviewReport(
        String institution, String generatedAt, Integer members,
        LeaversSection leavers, PasswordSetupSection passwordSetup, LoginSection logins, AccessSection access) {
}
