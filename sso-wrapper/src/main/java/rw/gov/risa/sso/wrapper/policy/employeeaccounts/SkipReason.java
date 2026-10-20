package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

/** Why nothing happened to an employee (the plan's {@code reason}). {@link #code()} is the wire value. */
public enum SkipReason {
    EMAIL_MISSING("email_missing"),
    EMAIL_INVALID("email_invalid"),
    EMAIL_DUPLICATE("email_duplicate"),
    NO_QUALIFYING_CLIENT("no_qualifying_client"),
    NOTHING_TO_ASSIGN("nothing_to_assign"),
    EMPLOYMENT_ENDED("employment_ended"),
    NOT_FOUND("not_found"),
    ACCOUNT_AMBIGUOUS("account_ambiguous"),
    ACCOUNT_OTHER_INSTITUTION("account_other_institution");

    private final String code;

    SkipReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
