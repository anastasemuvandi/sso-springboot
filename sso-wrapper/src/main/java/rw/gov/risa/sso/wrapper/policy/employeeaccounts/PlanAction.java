package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

/** What the plan intends for an employee. {@link #code()} is the wire value. */
public enum PlanAction {
    CREATE("create"),
    LINK("link"),
    SKIP("skip");

    private final String code;

    PlanAction(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
