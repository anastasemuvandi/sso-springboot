package rw.gov.risa.sso.wrapper.policy.overview;

import java.util.List;

/**
 * Section 3: login activity. {@code truncated}: Keycloak returned its maximum (10,000), so older
 * events inside the 7 days were not counted. {@code activeSessions}: institution = its own apps'
 * sessions; platform = the realm's.
 */
public record LoginSection(
        boolean available, String error,
        int logins24h, int logins7d, int failed24h, int failed7d, int people24h, int people7d,
        List<UserCount> mostFailures7d, List<UserCount> lockedNow, boolean truncated, Integer activeSessions) {

    public LoginSection {
        mostFailures7d = List.copyOf(mostFailures7d);
        lockedNow = List.copyOf(lockedNow);
    }

    public static LoginSection unavailable(String error, Integer activeSessions) {
        return new LoginSection(false, error, 0, 0, 0, 0, 0, 0, List.of(), List.of(), false, activeSessions);
    }
}
