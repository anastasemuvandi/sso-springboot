package rw.gov.risa.sso.wrapper.policy.accessmatrix;

/** The result of matching one employee to an account: a state and, for EXISTING, the user id. */
public record AccountMatch(AccountState state, String userId) {

    public static final AccountMatch NONE = new AccountMatch(AccountState.NONE, null);
    public static final AccountMatch ELSEWHERE = new AccountMatch(AccountState.ELSEWHERE, null);
    public static final AccountMatch AMBIGUOUS = new AccountMatch(AccountState.AMBIGUOUS, null);

    public static AccountMatch existing(String userId) {
        return new AccountMatch(AccountState.EXISTING, userId);
    }
}
