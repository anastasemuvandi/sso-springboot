package rw.gov.risa.sso.wrapper.policy.joinkey;

import java.util.Locale;
import java.util.Optional;

/** The fixed set of join columns a client may key on. {@link #value()} is the stored/wire value. */
public enum JoinColumn {
    NID("nid"),
    PHONE("phone"),
    EMAIL("email");

    private final String value;

    JoinColumn(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Exact (already-lowercased) lookup; empty when unknown. */
    public static Optional<JoinColumn> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (JoinColumn c : values()) {
            if (c.value.equals(value)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    public String upper() {
        return value.toUpperCase(Locale.ROOT);
    }
}
