package rw.gov.risa.sso.notification.detector;

import java.time.Duration;
import java.time.Instant;

/**
 * Per-rule de-duplication (reference {@code _is_duplicate}): a result is suppressed when an
 * <em>un-acknowledged</em> alert for the same rule and subject was created at or after
 * {@code now - cooldown}. Acknowledging an alert re-arms the rule immediately.
 */
public final class CooldownPolicy {

    private CooldownPolicy() {
    }

    public static Instant since(Instant now, int cooldownMinutes) {
        return now.minus(Duration.ofMinutes(Math.max(0, cooldownMinutes)));
    }

    /**
     * @param lastUnacknowledgedAlertAt creation time of the newest un-acknowledged alert for the
     *                                  same rule + subject, or null when there is none
     */
    public static boolean isSuppressed(Instant lastUnacknowledgedAlertAt, Instant now, int cooldownMinutes) {
        return lastUnacknowledgedAlertAt != null
                && !lastUnacknowledgedAlertAt.isBefore(since(now, cooldownMinutes));
    }
}
