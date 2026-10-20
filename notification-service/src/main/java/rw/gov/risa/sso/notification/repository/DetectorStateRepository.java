package rw.gov.risa.sso.notification.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import rw.gov.risa.sso.notification.detector.AuditEvent;

/** The detector watermark and the set of audit event ids already processed. */
@Repository
public class DetectorStateRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DetectorStateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Instant> watermark(String name) {
        return jdbc.query("SELECT watermark FROM detector_state WHERE name = :n", Map.of("n", name),
                (rs, i) -> Sql.instant(rs, "watermark")).stream().findFirst();
    }

    public void save(String name, Instant watermark, Instant runAt, int events, int alerts) {
        jdbc.update("""
                INSERT INTO detector_state (name, watermark, last_run_at, last_event_count, last_alert_count)
                VALUES (:n, :w, :r, :e, :a)
                ON CONFLICT (name) DO UPDATE SET watermark = EXCLUDED.watermark, last_run_at = EXCLUDED.last_run_at,
                    last_event_count = EXCLUDED.last_event_count, last_alert_count = EXCLUDED.last_alert_count
                """, new MapSqlParameterSource().addValue("n", name).addValue("w", Sql.ts(watermark))
                .addValue("r", Sql.ts(runAt)).addValue("e", events).addValue("a", alerts));
    }

    /** The subset of {@code ids} already marked processed. */
    public Set<String> alreadyProcessed(Collection<String> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        List<String> all = List.copyOf(ids);
        for (int i = 0; i < all.size(); i += 1000) {
            List<String> chunk = all.subList(i, Math.min(all.size(), i + 1000));
            out.addAll(jdbc.queryForList("SELECT event_id FROM processed_audit_events WHERE event_id IN (:ids)",
                    Map.of("ids", chunk), String.class));
        }
        return out;
    }

    public void markProcessed(Collection<AuditEvent> events, Instant at) {
        if (events.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = events.stream()
                .map(e -> new MapSqlParameterSource().addValue("id", e.id())
                        .addValue("c", Sql.ts(e.createdAt())).addValue("p", Sql.ts(at)))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO processed_audit_events (event_id, created_at, processed_at) VALUES (:id, :c, :p)
                ON CONFLICT (event_id) DO NOTHING
                """, batch);
    }

    public int pruneProcessedBefore(Instant before) {
        return jdbc.update("DELETE FROM processed_audit_events WHERE created_at < :b",
                new MapSqlParameterSource("b", Sql.ts(before)));
    }
}
