package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

/** The report's {@code account} fact: existing / created / not_created. {@link #code()} is the wire value. */
public enum AccountStatus {
    EXISTING("existing"),
    CREATED("created"),
    NOT_CREATED("not_created");

    private final String code;

    AccountStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
