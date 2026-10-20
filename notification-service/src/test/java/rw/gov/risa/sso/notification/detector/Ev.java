package rw.gov.risa.sso.notification.detector;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Test event builders. */
final class Ev {
    static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z"); // 12:00 Kigali
    private static final AtomicInteger SEQ = new AtomicInteger();

    private Ev() {
    }

    static AuditEvent fail(String user, String ip, Instant at) {
        return new AuditEvent("e" + SEQ.incrementAndGet(), "auth.login.failed", user, ip, null, at);
    }

    static AuditEvent ok(String user, String ip, String app, Instant at) {
        return new AuditEvent("e" + SEQ.incrementAndGet(), "auth.login.success", user, ip, app, at);
    }

    static AuditEvent of(String type, String user, String ip, String app, Instant at) {
        return new AuditEvent("e" + SEQ.incrementAndGet(), type, user, ip, app, at);
    }

    static Instant min(double minutes) {
        return T0.plusMillis((long) (minutes * 60_000));
    }

    static Predicate<AuditEvent> all() {
        return e -> true;
    }

    static Predicate<AuditEvent> only(List<AuditEvent> fresh) {
        Set<String> ids = fresh.stream().map(AuditEvent::id).collect(Collectors.toSet());
        return e -> ids.contains(e.id());
    }
}
