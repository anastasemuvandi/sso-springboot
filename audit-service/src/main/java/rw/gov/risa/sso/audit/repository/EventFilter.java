package rw.gov.risa.sso.audit.repository;

import java.time.OffsetDateTime;

/** Exact-match filters of {@code GET /audit/events} and the exports (reference core/query.py). */
public record EventFilter(
        String eventType,
        String category,
        String userId,
        String appId,
        String realm,
        String institution,
        String target,
        String severity,
        OffsetDateTime fromDate,
        OffsetDateTime toDate) {

    public static EventFilter none() {
        return new EventFilter(null, null, null, null, null, null, null, null, null, null);
    }

    public static EventFilter window(OffsetDateTime from, OffsetDateTime to) {
        return new EventFilter(null, null, null, null, null, null, null, null, from, to);
    }
}
