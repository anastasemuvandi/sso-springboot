package rw.gov.risa.sso.wrapper.policy.audit;

/** Audit outcomes and the severity rule: {@code failure}/{@code denied} → WARNING, else INFO. */
public final class AuditOutcome {

    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";
    public static final String DENIED = "denied";

    public static final String SEVERITY_INFO = "INFO";
    public static final String SEVERITY_WARNING = "WARNING";

    private AuditOutcome() {
    }

    public static String severityFor(String outcome) {
        return FAILURE.equals(outcome) || DENIED.equals(outcome) ? SEVERITY_WARNING : SEVERITY_INFO;
    }
}
