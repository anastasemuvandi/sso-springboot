package rw.gov.risa.sso.audit.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import rw.gov.risa.sso.audit.api.dto.ServiceHealthRead;

@Repository
public class ServiceHealthRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonColumns json;
    private final RowMapper<ServiceHealthRead> mapper;

    public ServiceHealthRepository(NamedParameterJdbcTemplate jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new ServiceHealthRead(rs.getObject("id", UUID.class), rs.getString("service_name"),
                rs.getString("status"), (Integer) rs.getObject("response_time_ms"), json.read(rs.getString("details")),
                rs.getObject("checked_at", OffsetDateTime.class));
    }

    public void insert(String service, String status, Integer responseTimeMs, Map<String, Object> details,
                       OffsetDateTime checkedAt) {
        jdbc.update("INSERT INTO service_health (service_name, status, response_time_ms, details, checked_at) "
                        + "VALUES (:s, :st, :ms, CAST(:d AS jsonb), :t)",
                new MapSqlParameterSource("s", service).addValue("st", status).addValue("ms", responseTimeMs)
                        .addValue("d", json.write(details)).addValue("t", checkedAt));
    }

    public List<ServiceHealthRead> latest(int limit) {
        return jdbc.query("SELECT * FROM service_health ORDER BY checked_at DESC LIMIT :l",
                new MapSqlParameterSource("l", limit), mapper);
    }

    public List<ServiceHealthRead> history(String service, int limit) {
        return jdbc.query("SELECT * FROM service_health WHERE service_name = :s ORDER BY checked_at DESC LIMIT :l",
                new MapSqlParameterSource("s", service).addValue("l", limit), mapper);
    }

    public int pruneOlderThan(OffsetDateTime cutoff) {
        return jdbc.update("DELETE FROM service_health WHERE checked_at < :c", new MapSqlParameterSource("c", cutoff));
    }
}
