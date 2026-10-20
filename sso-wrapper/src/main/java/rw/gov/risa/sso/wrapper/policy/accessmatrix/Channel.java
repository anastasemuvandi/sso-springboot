package rw.gov.risa.sso.wrapper.policy.accessmatrix;

import java.util.Optional;

/**
 * Who asked for a change. {@code auto_provisioning} is written by the provisioning service only;
 * the bulk endpoint accepts {@link #UI} and {@link #BULK}.
 */
public enum Channel {
    UI("ui"),
    BULK("bulk"),
    AUTO_PROVISIONING("auto_provisioning");

    private final String code;

    Channel(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** May a request to the bulk endpoint name this channel? (Python {@code REQUEST_CHANNELS}). */
    public boolean isRequestChannel() {
        return this == UI || this == BULK;
    }

    public static Optional<Channel> fromCode(String code) {
        for (Channel c : values()) {
            if (c.code.equals(code)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
