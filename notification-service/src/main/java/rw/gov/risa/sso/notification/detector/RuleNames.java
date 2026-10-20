package rw.gov.risa.sso.notification.detector;

/** Rule names (also the {@code rule_name} column and the {@code /config/{rule_name}} key). */
public final class RuleNames {
    public static final String BRUTE_FORCE = "brute_force";
    public static final String UNUSUAL_HOURS = "unusual_hours";
    public static final String IMPOSSIBLE_TRAVEL = "impossible_travel";
    public static final String ANOMALOUS_ACCESS = "anomalous_access";
    /** Placeholder in the reference: needs live Keycloak sessions; disabled, never evaluated. */
    public static final String STALE_SESSION = "stale_session";
    /** Placeholder in the reference: needs live Keycloak sessions; disabled, never evaluated. */
    public static final String MULTIPLE_SESSIONS = "multiple_sessions";

    private RuleNames() {
    }
}
