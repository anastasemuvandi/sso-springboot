package rw.gov.risa.sso.wrapper.policy.clientaccess;

/** Why {@link ClientAccessDecider#decide} answered as it did. Stable wire strings. */
public enum AccessReason {
    EXEMPT("exempt"),
    NO_POLICY("no_policy"),
    DENIED("denied"),
    ASSIGNED("assigned"),
    NOT_ASSIGNED("not_assigned"),
    DENY_LIST_ONLY("deny_list_only"),
    EMPTY_POLICIES("empty_policies");

    private final String wire;

    AccessReason(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
