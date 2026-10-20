package rw.gov.risa.sso.wrapper.policy.clientaccess;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** What a platform admin sees for one (person, client) cell. */
public enum AccessState {
    /** Everyone may log in; nothing to grant or revoke. */
    EXEMPT("exempt"),
    /** Admitted by name and/or through a group. */
    GRANTED("granted"),
    /** Admitted because the client restricts nobody (or only others). */
    OPEN("open"),
    /** On the client's deny-list, by name or by group. */
    REVOKED("revoked"),
    /** Nothing admits them. */
    NOT_GRANTED("not_granted"),
    /** The client's policies could not be read (the view reports the error). */
    UNKNOWN("unknown");

    public static final Set<AccessState> GRANTABLE = EnumSet.of(NOT_GRANTED, REVOKED);
    public static final Set<AccessState> REVOCABLE = EnumSet.of(GRANTED, OPEN);

    private final String wire;

    AccessState(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<AccessState> fromWire(String value) {
        for (AccessState s : values()) {
            if (s.wire.equals(value)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
