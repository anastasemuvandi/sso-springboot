package rw.gov.risa.sso.wrapper.policy.accessmatrix;

/** How an employee row relates to an SSO account. */
public enum AccountState {
    /** A member of the institution: cells are live. */
    EXISTING("existing"),
    /** No account: no access; a grant creates one. */
    NONE("none"),
    /** Linked to an account outside the institution (row locked). */
    ELSEWHERE("elsewhere"),
    /** Two employees resolve to one account (row locked). */
    AMBIGUOUS("ambiguous");

    private final String code;

    AccountState(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Python {@code LOCKED_ACCOUNTS}: elsewhere and ambiguous. */
    public boolean locked() {
        return this == ELSEWHERE || this == AMBIGUOUS;
    }
}
