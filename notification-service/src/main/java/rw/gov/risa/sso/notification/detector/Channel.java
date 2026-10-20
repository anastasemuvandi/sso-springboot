package rw.gov.risa.sso.notification.detector;

import java.util.Optional;

/** Delivery channels. The wire/DB value is the lower-case name. */
public enum Channel {
    EMAIL("email"), INAPP("inapp"), WEBHOOK("webhook");

    private final String wire;

    Channel(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<Channel> parse(String s) {
        for (Channel c : values()) {
            if (c.wire.equals(s)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
