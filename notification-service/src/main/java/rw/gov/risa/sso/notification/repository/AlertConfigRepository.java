package rw.gov.risa.sso.notification.repository;

import java.util.LinkedHashMap;
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
public class AlertConfigRepository {

    private static final TypeReference<List<String>> LIST = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<AlertConfigRow> rowMapper;

    public AlertConfigRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rowMapper = (rs, n) -> new AlertConfigRow(
                rs.getObject("id", UUID.class),
                rs.getString("rule_name"),
                rs.getBoolean("enabled"),
                Sql.intOrNull(rs, "threshold"),
                Sql.intOrNull(rs, "window_minutes"),
                rs.getString("severity"),
                readList(rs.getString("channels")),
                Sql.intOrNull(rs, "cooldown_minutes"),
                Sql.instant(rs, "updated_at"));
    }

    public Map<String, AlertConfigRow> findAll() {
        Map<String, AlertConfigRow> out = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM alert_configs ORDER BY rule_name", rowMapper).forEach(r -> out.put(r.ruleName(), r));
        return out;
    }

    public Optional<AlertConfigRow> find(String ruleName) {
        return jdbc.query("SELECT * FROM alert_configs WHERE rule_name = :r", Map.of("r", ruleName), rowMapper)
                .stream().findFirst();
    }

    public AlertConfigRow upsert(AlertConfigRow r) {
        jdbc.update("""
                INSERT INTO alert_configs (id, rule_name, enabled, threshold, window_minutes, severity, channels,
                                           cooldown_minutes, updated_at)
                VALUES (:id, :rule, :enabled, :threshold, :window, :severity, CAST(:channels AS jsonb), :cooldown,
                        :updated)
                ON CONFLICT (rule_name) DO UPDATE SET
                    enabled = EXCLUDED.enabled, threshold = EXCLUDED.threshold,
                    window_minutes = EXCLUDED.window_minutes, severity = EXCLUDED.severity,
                    channels = EXCLUDED.channels, cooldown_minutes = EXCLUDED.cooldown_minutes,
                    updated_at = EXCLUDED.updated_at
                """, new MapSqlParameterSource()
                .addValue("id", r.id())
                .addValue("rule", r.ruleName())
                .addValue("enabled", r.enabled())
                .addValue("threshold", r.threshold())
                .addValue("window", r.windowMinutes())
                .addValue("severity", r.severity())
                .addValue("channels", r.channels() == null ? null : writeJson(r.channels()))
                .addValue("cooldown", r.cooldownMinutes())
                .addValue("updated", Sql.ts(r.updatedAt())));
        return find(r.ruleName()).orElseThrow();
    }

    private List<String> readList(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt alert_configs.channels", e);
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
