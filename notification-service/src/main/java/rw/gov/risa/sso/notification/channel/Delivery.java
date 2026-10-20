package rw.gov.risa.sso.notification.channel;

/**
 * Outcome of one delivery attempt, persisted as one {@code notifications} row.
 * {@code status} is {@code sent}, {@code failed} or {@code skipped} (channel not configured
 * or nobody to send to — recorded, never silently reported as sent).
 */
public record Delivery(String channel, String recipient, String status, String error) {

    public static final String SENT = "sent";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";

    public static Delivery sent(String channel, String recipient) {
        return new Delivery(channel, recipient, SENT, null);
    }

    public static Delivery skipped(String channel, String recipient, String reason) {
        return new Delivery(channel, recipient, SKIPPED, reason);
    }

    public static Delivery failed(String channel, String recipient, Throwable t) {
        String msg = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        return new Delivery(channel, recipient, FAILED, msg.length() > 500 ? msg.substring(0, 500) : msg);
    }
}
