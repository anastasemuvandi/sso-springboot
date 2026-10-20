package rw.gov.risa.sso.wrapper.policy.overview;

import java.util.List;

/**
 * {@link Overview#loginActivity}: counts over 24 h / 7 days, the top 5 failing users (usernames not
 * yet filled), and lockout candidates (users with a failure inside the 15-minute lockout window,
 * newest first, at most 50) for the caller to check against the brute-force detector.
 */
public record LoginActivity(
        int logins24h, int logins7d, int failed24h, int failed7d, int people24h, int people7d,
        List<UserCount> mostFailures7d, List<String> lockoutCandidates) {

    public LoginActivity {
        mostFailures7d = List.copyOf(mostFailures7d);
        lockoutCandidates = List.copyOf(lockoutCandidates);
    }
}
