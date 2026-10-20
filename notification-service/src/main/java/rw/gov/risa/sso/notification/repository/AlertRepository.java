package rw.gov.risa.sso.notification.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AlertRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<AlertRow> rowMapper;

    public AlertRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rowMapper = (rs, n) -> new AlertRow(
                rs.getObject("id", UUID.class),
                rs.getString("rule_name"),
                rs.getString("severity"),
                rs.getString("user_id"),
                rs.getString("ip_address"),
                rs.getString("app_id"),
                rs.getString("subject_key"),
                readMap(rs.getString("details")),
                rs.getBoolean("acknowledged"),
                rs.getString("acknowledged_by"),
                Sql.instant(rs, "acknowledged_at"),
                Sql.instant(rs, "created_at"));
    }

    public void insert(AlertRow a) {
        jdbc.update("""
                INSERT INTO alerts (id, rule_name, severity, user_id, ip_address, app_id, subject_key, details,
                                    acknowledged, created_at)
                VALUES (:id, :rule, :sev, :user, :ip, :app, :subject, CAST(:details AS jsonb), FALSE, :created)
                """, new MapSqlParameterSource()
                .addValue("id", a.id())
                .addValue("rule", a.ruleName())
                .addValue("sev", a.severity())
                .addValue("user", a.userId())
                .addValue("ip", a.ipAddress())
                .addValue("app", a.appId())
                .addValue("subject", a.subjectKey())
                .addValue("details", writeJson(a.details()))
                .addValue("created", Sql.ts(a.createdAt())));
    }

    public Optional<AlertRow> findById(UUID id) {
        return jdbc.query("SELECT * FROM alerts WHERE id = :id", Map.of("id", id), rowMapper).stream().findFirst();
    }

    public List<AlertRow> list(String severity, Boolean acknowledged, String ruleName, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM alerts WHERE TRUE");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (severity != null) {
            sql.append(" AND severity = :sev");
            p.addValue("sev", severity);
        }
        if (acknowledged != null) {
            sql.append(" AND acknowledged = :ack");
            p.addValue("ack", acknowledged);
        }
        if (ruleName != null) {
            sql.append(" AND rule_name = :rule");
            p.addValue("rule", ruleName);
        }
        sql.append(" ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset");
        p.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), p, rowMapper);
    }

    public Optional<AlertRow> acknowledge(UUID id, String by, Instant at) {
        int n = jdbc.update("""
                UPDATE alerts SET acknowledged = TRUE, acknowledged_by = :by, acknowledged_at = :at WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("by", by).addValue("at", Sql.ts(at)));
        return n == 0 ? Optional.empty() : findById(id);
    }

    /** Newest un-acknowledged alert for rule + subject created at/after {@code since}. */
    public Optional<Instant> lastUnacknowledgedSince(String ruleName, String subjectKey, Instant since) {
        return jdbc.query("""
                        SELECT created_at FROM alerts
                        WHERE rule_name = :rule AND subject_key = :subject AND NOT acknowledged AND created_at >= :since
                        ORDER BY created_at DESC LIMIT 1
                        """,
                new MapSqlParameterSource().addValue("rule", ruleName).addValue("subject", subjectKey)
                        .addValue("since", Sql.ts(since)),
                (rs, n) -> Sql.instant(rs, "created_at")).stream().findFirst();
    }

    public List<AlertRow> createdSince(Instant since) {
        return jdbc.query("SELECT * FROM alerts WHERE created_at >= :since ORDER BY created_at DESC, id",
                new MapSqlParameterSource("since", Sql.ts(since)), rowMapper);
    }

    private Map<String, Object> readMap(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt alerts.details", e);
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o == null ? Map.of() : o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
