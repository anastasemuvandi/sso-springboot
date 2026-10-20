package rw.gov.risa.sso.wrapper.policy.ippisperson;

/** The union of per-user statuses the IPPIS sync/leaver/reconcile passes report. */
public enum IppisOutcome {
    UPDATED("updated"),
    UNCHANGED("unchanged"),
    DISABLED("disabled"),
    ALREADY_DISABLED("already_disabled"),
    ACTIVE("active"),
    SKIPPED("skipped"),
    NOT_FOUND("not_found"),
    NO_USER("no_user"),
    ERROR("error");

    private final String wire;

    IppisOutcome(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
