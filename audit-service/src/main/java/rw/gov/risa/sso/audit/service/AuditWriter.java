package rw.gov.risa.sso.audit.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import rw.gov.risa.sso.audit.api.ApiException;
import rw.gov.risa.sso.audit.api.dto.AuditEventCreate;
import rw.gov.risa.sso.audit.api.dto.AuditEventRead;
import rw.gov.risa.sso.audit.chain.EventCategories;
import rw.gov.risa.sso.audit.chain.HashChain;
import rw.gov.risa.sso.audit.repository.AuditEventRepository;
import rw.gov.risa.sso.audit.repository.JsonColumns;

/**
 * The only way a row enters {@code audit_logs} (reference {@code core/logger.py write_event}).
 * Used by the ingest endpoint and by the service's own workers, so the hash formula and the
 * chain serialisation live in exactly one place.
 *
 * <p><b>Serialisation.</b> Each append locks the single {@code audit_chain_head} row
 * ({@code SELECT ... FOR UPDATE}), links to its hash, inserts, and moves the head, all in one
 * transaction. A concurrent writer blocks on the head row until this one commits and then reads
 * the new head, so two writers can never claim the same {@code prev_hash}.
 *
 * <p><b>Ordering.</b> {@code created_at} is taken under the lock and forced strictly after the
 * previous head's ({@code +1µs} if the clock has not moved or went backwards), so "ORDER BY
 * created_at" is always chain order, which is what the verifier walks.
 */
@Service
public class AuditWriter {

    private final AuditEventRepository events;
    private final JsonColumns json;
    private final Clock clock;
    private final Counter written;

    public AuditWriter(AuditEventRepository events, JsonColumns json, Clock clock, MeterRegistry meters) {
        this.events = events;
        this.json = json;
        this.clock = clock;
        this.written = Counter.builder("audit_events_written_total")
                .description("Audit events appended to the hash chain").register(meters);
    }

    /** Joins the caller's transaction if there is one (workers batch tracking updates with events). */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEventRead append(AuditEventCreate in) {
        Map<String, Object> details = normalise(in.details());

        AuditEventRepository.ChainHead head = events.lockHead();
        OffsetDateTime createdAt = HashChain.toMicros(OffsetDateTime.now(clock));
        if (head.lastCreatedAt() != null && !createdAt.isAfter(head.lastCreatedAt())) {
            createdAt = HashChain.toMicros(head.lastCreatedAt()).plusNanos(1000);
        }

        String severity = in.severity().name();
        int version = HashChain.CURRENT_HASH_VERSION;
        HashChain.HashInput hashInput = new HashChain.HashInput(in.eventType(), in.userId(), createdAt, details,
                in.performedBy(), in.institution(), in.target(), in.appId(), in.ipAddress(), severity);
        String hash = HashChain.compute(hashInput, head.lastHash(), version);

        AuditEventRead row = new AuditEventRead(UUID.randomUUID(), in.eventType(),
                EventCategories.infer(in.eventType()), in.userId(), in.employeeId(), in.appId(), in.realm(),
                in.institution(), in.target(), in.performedBy(), in.ipAddress(), details, severity, head.lastHash(),
                hash, version, createdAt);
        events.insert(row);
        events.advanceHead(hash, createdAt);
        written.increment();
        return row;
    }

    private Map<String, Object> normalise(Map<String, Object> details) {
        String text;
        try {
            text = events.normaliseJson(json.write(details));
        } catch (DataAccessException e) {
            // e.g. a \u0000 in a string, which jsonb cannot store.
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error",
                    "details cannot be stored as JSONB", Map.of("fields", Map.of("details", "invalid")));
        }
        return json.read(text);
    }
}
