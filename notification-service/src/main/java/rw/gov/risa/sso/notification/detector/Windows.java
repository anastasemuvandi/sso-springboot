package rw.gov.risa.sso.notification.detector;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Sliding-window helpers shared by the rules. */
final class Windows {
    static final Comparator<AuditEvent> CHRONOLOGICAL =
            Comparator.comparing(AuditEvent::createdAt).thenComparing(AuditEvent::id);

    private Windows() {
    }

    /**
     * Events matching {@code filter} with {@code anchor - window <= createdAt <= anchor}.
     * Both edges are inclusive: the reference kept events with {@code ts >= cutoff}, and an
     * event cannot be "after" the anchor it is compared with.
     */
    static List<AuditEvent> within(List<AuditEvent> sorted, Instant anchor, int windowMinutes,
                                   Predicate<AuditEvent> filter) {
        Instant from = anchor.minus(Duration.ofMinutes(windowMinutes));
        return sorted.stream()
                .filter(e -> !e.createdAt().isBefore(from) && !e.createdAt().isAfter(anchor))
                .filter(filter)
                .toList();
    }
}
