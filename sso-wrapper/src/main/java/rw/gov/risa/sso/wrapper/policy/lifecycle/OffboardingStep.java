package rw.gov.risa.sso.wrapper.policy.lifecycle;

/** The ordered steps of an offboarding; the order is part of the rule. */
public enum OffboardingStep {
    /** 1. End every session (immediate, irreversible — not rolled back). */
    KILL_SESSIONS,
    /** 2. Disable the account (no new logins) and stamp the offboarding markers. */
    DISABLE_ACCOUNT,
    /** 3. Remove from every group. */
    REMOVE_GROUPS,
    /** 4. Strip {@code inst:<slug>:*} realm roles. */
    STRIP_INSTITUTION_ROLES,
    /** 5. Remove the person from every client's {@code sso-access-users}. */
    REMOVE_NAMED_GRANTS
}
