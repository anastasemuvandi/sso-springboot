package rw.gov.risa.sso.wrapper.policy.ippisperson;

import java.util.Locale;
import java.util.Optional;

/** The flat contract's three-state status ({@code schema/ippis.py EmploymentStatus}). */
public enum EmploymentStatus {
    ACTIVE("active"),
    SUSPENDED("suspended"),
    TERMINATED("terminated");

    private final String wire;

    EmploymentStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<EmploymentStatus> fromWire(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        for (EmploymentStatus s : values()) {
            if (s.wire.equals(v)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
