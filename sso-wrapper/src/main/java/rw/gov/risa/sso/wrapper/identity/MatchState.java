package rw.gov.risa.sso.wrapper.identity;

import java.util.Locale;

/** {@code employee_sso_link.match_state}: what the sync resolved about an employee's Keycloak account. */
public enum MatchState {
    MATCHED, UNMATCHED, AMBIGUOUS;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
