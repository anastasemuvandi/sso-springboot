package rw.gov.risa.sso.wrapper.policy.joinkey;

/** Why a token was refused at a door (403). {@link #code()} is the stable machine code. */
public enum JoinKeyError {
    MISSING("join_key_missing"),
    INVALID("join_key_invalid"),
    NOT_UNIQUE("join_key_not_unique");

    private final String code;

    JoinKeyError(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
