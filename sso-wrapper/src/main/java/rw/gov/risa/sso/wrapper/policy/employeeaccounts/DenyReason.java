package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

/** Why one client refused one employee. {@link #code()} is the wire value. */
public enum DenyReason {
    COLUMN_UNSET("join_column_unset"),
    VALUE_MISSING("join_value_missing"),
    VALUE_INVALID("join_value_invalid");

    private final String code;

    DenyReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
