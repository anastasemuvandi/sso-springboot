package rw.gov.risa.sso.wrapper.policy.accessmatrix;

import java.util.Optional;

/** A bulk change's direction. Wire values {@code grant} / {@code revoke}. */
public enum Action {
    GRANT("grant"),
    REVOKE("revoke");

    private final String code;

    Action(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<Action> fromCode(String code) {
        for (Action a : values()) {
            if (a.code.equals(code)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }
}
