package rw.gov.risa.sso.audit.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import rw.gov.risa.sso.audit.api.dto.AuditEventCreate;
import rw.gov.risa.sso.audit.api.dto.AuditEventRead;
import rw.gov.risa.sso.audit.api.dto.EventPage;
import rw.gov.risa.sso.audit.repository.AuditEventRepository;
import rw.gov.risa.sso.audit.repository.EventFilter;

/** Ingest and read paths of {@code /audit/events} (reference routes/events.py + core/query.py). */
@Service
public class EventService {

    private final AuditWriter writer;
    private final AuditEventRepository events;

    public EventService(AuditWriter writer, AuditEventRepository events) {
        this.writer = writer;
        this.events = events;
    }

    /**
     * Emitters that put institution/target only in {@code details} keep working: lift them into the
     * columns rather than dropping them, or the event is invisible to the per-institution view.
     * Only an absent (null) column is filled; a value the caller sent is kept as-is.
     */
    public AuditEventRead ingest(AuditEventCreate payload) {
        String institution = payload.institution();
        if (institution == null) {
            institution = detailString(payload.details(), "institution");
        }
        String target = payload.target();
        if (target == null) {
            target = detailString(payload.details(), "target_user", "target");
        }
        return writer.append(payload.withInstitutionAndTarget(institution, target));
    }

    /** First non-blank string value among {@code keys}, stripped; else null. */
    static String detailString(Map<String, Object> details, String... keys) {
        if (details == null) {
            return null;
        }
        for (String k : keys) {
            if (details.get(k) instanceof String s && !s.isBlank()) {
                return s.strip();
            }
        }
        return null;
    }

    @Transactional(readOnly = true)
    public EventPage list(EventFilter filter, int limit, int offset) {
        long total = events.count(filter);
        return new EventPage(total, limit, offset, events.find(filter, limit, offset));
    }

    @Transactional(readOnly = true)
    public List<AuditEventRead> forUser(String userId, OffsetDateTime from, OffsetDateTime to, int limit) {
        return events.findByUser(userId, from, to, limit);
    }

    @Transactional(readOnly = true)
    public List<AuditEventRead> find(EventFilter filter, int limit) {
        return events.find(filter, limit, 0);
    }
}
