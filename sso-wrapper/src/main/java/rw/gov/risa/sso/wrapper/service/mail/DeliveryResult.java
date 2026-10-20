package rw.gov.risa.sso.wrapper.service.mail;

/**
 * How one send went. {@code detail} is a short human reason for {@code FAILED}/{@code NOT_CONFIGURED}
 * (the exception class and message — never credentials), null when sent.
 */
public record DeliveryResult(Status status, String detail) {

    public enum Status {
        SENT("sent"),
        FAILED("failed"),
        NOT_CONFIGURED("not_configured");

        private final String wire;

        Status(String wire) {
            this.wire = wire;
        }

        /** {@code sent | failed | not_configured} — the delivery-record and report value. */
        public String wire() {
            return wire;
        }
    }

    public static DeliveryResult sent() {
        return new DeliveryResult(Status.SENT, null);
    }

    public static DeliveryResult failed(String detail) {
        return new DeliveryResult(Status.FAILED, detail);
    }

    public static DeliveryResult notConfigured() {
        return new DeliveryResult(Status.NOT_CONFIGURED, "not configured (SMTP_HOST is unset)");
    }

    public boolean isSent() {
        return status == Status.SENT;
    }

    /** The provisioning-report form: {@code sent}, {@code not_configured}, {@code failed: <why>}. */
    public String report() {
        return status == Status.FAILED ? "failed: " + detail : status.wire();
    }
}
