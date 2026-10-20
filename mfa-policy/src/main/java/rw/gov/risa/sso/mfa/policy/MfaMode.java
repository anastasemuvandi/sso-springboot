package rw.gov.risa.sso.mfa.policy;

import java.util.Locale;
import java.util.Optional;

/** The realm TOTP mode, as stored in {@link MfaConstants#MODE_ATTRIBUTE}. */
public enum MfaMode {
    /** Nobody is asked, on any client. */
    OFF("off"),
    /** A client requires TOTP unless its {@link MfaConstants#CLIENT_ATTRIBUTE} is {@code false}. */
    PER_CLIENT("per_client"),
    /** Every client requires it (the original behaviour, and the fail-closed default). */
    ALL("all");

    private final String value;

    MfaMode(String value) {
        this.value = value;
    }

    /** The stored attribute value ({@code off}, {@code per_client}, {@code all}). */
    public String value() {
        return value;
    }

    /** Exact match after trim + lower-case; empty for null, blank or unknown values. */
    public static Optional<MfaMode> fromValue(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String m = raw.trim().toLowerCase(Locale.ROOT);
        for (MfaMode mode : values()) {
            if (mode.value.equals(m)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return value;
    }
}
