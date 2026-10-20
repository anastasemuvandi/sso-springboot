package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

/** What actually happened to an employee's row (or would, in a dry run). {@link #code()} is the wire value. */
public enum Outcome {
    CREATED("created"),
    LINKED("linked"),
    SKIPPED("skipped"),
    FAILED("failed");

    private final String code;

    Outcome(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
