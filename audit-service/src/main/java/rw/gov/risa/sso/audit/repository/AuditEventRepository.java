package rw.gov.risa.sso.audit.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import rw.gov.risa.sso.audit.api.dto.AuditEventRead;

/**
 * Plain JDBC over {@code audit_logs}, {@code audit_chain_head} and {@code audit_purged_links}.
 * The table is append-only at the DB level (V2 trigger); this class therefore only inserts,
 * except {@link #purgeOlderThan}, which must run inside a transaction that has called
 * {@link #enableRetentionPurge()}.
 */
@Repository
public class AuditEventRepository {

    private static final String COLUMNS = "id, event_type, category, user_id, employee_id, app_id, realm, "
            + "institution, target, performed_by, ip_address, details::text AS details, severity, prev_hash, "
            + "event_hash, hash_version, created_at";

    /** Upper bound on a purged run the verifier will walk while bridging one gap. */
    static final int MAX_BRIDGE_DEPTH = 10_000_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate streaming;
    private final JsonColumns json;
    private final RowMapper<AuditEventRead> mapper;

    public AuditEventRepository(DataSource dataSource, JsonColumns json) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcTemplate fetching = new JdbcTemplate(dataSource);
        fetching.setFetchSize(1000);
        this.streaming = new NamedParameterJdbcTemplate(fetching);
        this.json = json;
        this.mapper = this::mapRow;
    }

    /** The chain head, locked until the surrounding transaction ends. */
    public record ChainHead(String lastHash, OffsetDateTime lastCreatedAt) {
    }

    public ChainHead lockHead() {
        return jdbc.getJdbcTemplate().queryForObject(
                "SELECT last_hash, last_created_at FROM audit_chain_head WHERE id = 1 FOR UPDATE",
                (rs, i) -> new ChainHead(rs.getString(1), rs.getObject(2, OffsetDateTime.class)));
    }

    public String headHash() {
        return jdbc.getJdbcTemplate().queryForObject("SELECT last_hash FROM audit_chain_head WHERE id = 1",
                String.class);
    }

    public void advanceHead(String hash, OffsetDateTime createdAt) {
        jdbc.update("UPDATE audit_chain_head SET last_hash = :h, last_created_at = :t, "
                        + "event_count = event_count + 1, updated_at = now() WHERE id = 1",
                new MapSqlParameterSource("h", hash).addValue("t", createdAt));
    }

    /**
     * Round-trips {@code details} through jsonb and returns Postgres's text form. Hashing what the
     * database will hand back (not what the caller sent) keeps writer and verifier in agreement
     * even where jsonb normalises (e.g. {@code 1e2} → {@code 100}, duplicate keys).
     */
    public String normaliseJson(String detailsJson) {
        return jdbc.queryForObject("SELECT CAST(:j AS jsonb)::text", new MapSqlParameterSource("j", detailsJson),
                String.class);
    }

    public void insert(AuditEventRead e) {
        jdbc.update("INSERT INTO audit_logs (id, event_type, category, user_id, employee_id, app_id, realm, "
                        + "institution, target, performed_by, ip_address, details, severity, prev_hash, event_hash, "
                        + "hash_version, created_at) VALUES (:id, :event_type, :category, :user_id, :employee_id, "
                        + ":app_id, :realm, :institution, :target, :performed_by, :ip_address, CAST(:details AS jsonb), "
                        + ":severity, :prev_hash, :event_hash, :hash_version, :created_at)",
                new MapSqlParameterSource()
                        .addValue("id", e.id())
                        .addValue("event_type", e.eventType())
                        .addValue("category", e.category())
                        .addValue("user_id", e.userId())
                        .addValue("employee_id", e.employeeId())
                        .addValue("app_id", e.appId())
                        .addValue("realm", e.realm())
                        .addValue("institution", e.institution())
                        .addValue("target", e.target())
                        .addValue("performed_by", e.performedBy())
                        .addValue("ip_address", e.ipAddress())
                        .addValue("details", json.write(e.details()))
                        .addValue("severity", e.severity())
                        .addValue("prev_hash", e.prevHash())
                        .addValue("event_hash", e.eventHash())
                        .addValue("hash_version", e.hashVersion())
                        .addValue("created_at", e.createdAt()));
    }

    public long count(EventFilter f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(f, p);
        Long n = jdbc.queryForObject("SELECT count(*) FROM audit_logs" + where, p, Long.class);
        return n == null ? 0 : n;
    }

    /** Newest first, as the reference. */
    public List<AuditEventRead> find(EventFilter f, int limit, int offset) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(f, p);
        p.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query("SELECT " + COLUMNS + " FROM audit_logs" + where
                + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset", p, mapper);
    }

    public List<AuditEventRead> findByUser(String userId, OffsetDateTime from, OffsetDateTime to, int limit) {
        EventFilter f = new EventFilter(null, null, userId, null, null, null, null, null, from, to);
        return find(f, limit, 0);
    }

    /** Streams rows oldest first (chain order). Must run inside a transaction for cursor fetching. */
    public void streamChain(OffsetDateTime from, OffsetDateTime to, Consumer<AuditEventRead> sink) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(EventFilter.window(from, to), p);
        streaming.query("SELECT " + COLUMNS + " FROM audit_logs" + where + " ORDER BY created_at ASC, id ASC", p,
                rs -> {
                    sink.accept(mapRow(rs, 0));
                });
    }

    /** Hash of the newest row strictly before {@code from}, the anchor for a windowed check. */
    public String hashBefore(OffsetDateTime from) {
        List<String> r = jdbc.queryForList("SELECT event_hash FROM audit_logs WHERE created_at < :f "
                + "ORDER BY created_at DESC, id DESC LIMIT 1", new MapSqlParameterSource("f", from), String.class);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Is there an unbroken run of retention-purged rows from {@code fromHash} to {@code toHash}? */
    public boolean bridges(String fromHash, String toHash) {
        Boolean b = jdbc.queryForObject("WITH RECURSIVE walk(h, depth) AS ("
                        + " SELECT event_hash, 1 FROM audit_purged_links WHERE prev_hash = :from"
                        + " UNION ALL"
                        + " SELECT p.event_hash, w.depth + 1 FROM audit_purged_links p JOIN walk w ON p.prev_hash = w.h"
                        + " WHERE w.depth < :max)"
                        + " SELECT EXISTS (SELECT 1 FROM walk WHERE h = :to)",
                new MapSqlParameterSource("from", fromHash).addValue("to", toHash).addValue("max", MAX_BRIDGE_DEPTH),
                Boolean.class);
        return Boolean.TRUE.equals(b);
    }

    public List<String> distinctCategories() {
        return jdbc.getJdbcTemplate().queryForList("SELECT DISTINCT category FROM audit_logs", String.class);
    }

    /** Allows the DELETEs of the current transaction past the append-only trigger. */
    public void enableRetentionPurge() {
        jdbc.getJdbcTemplate().queryForObject("SELECT set_config('gorsso.audit_retention_purge', 'on', true)",
                String.class);
    }

    public int purgeOlderThan(String category, OffsetDateTime cutoff) {
        return jdbc.update("DELETE FROM audit_logs WHERE category = :c AND created_at < :cutoff",
                new MapSqlParameterSource("c", category).addValue("cutoff", cutoff));
    }

    // ---- access review (reference workers/access_review.py) ----

    public List<String> institutions() {
        return jdbc.getJdbcTemplate().queryForList("SELECT DISTINCT institution FROM audit_logs "
                + "WHERE institution IS NOT NULL AND institution <> '' ORDER BY institution", String.class);
    }

    public Map<String, Long> countByType(String institution, OffsetDateTime since, List<String> types) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT event_type, count(*) FROM audit_logs WHERE institution = :i AND created_at >= :s "
                        + "AND event_type IN (:types) GROUP BY event_type ORDER BY event_type",
                new MapSqlParameterSource("i", institution).addValue("s", since).addValue("types", types),
                rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                });
        return out;
    }

    /** By actor, most active first; a null actor is one group, reported as "unknown". */
    public Map<String, Long> countByActor(String institution, OffsetDateTime since, List<String> types) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT performed_by, count(*) FROM audit_logs WHERE institution = :i AND created_at >= :s "
                        + "AND event_type IN (:types) GROUP BY performed_by ORDER BY count(*) DESC, performed_by",
                new MapSqlParameterSource("i", institution).addValue("s", since).addValue("types", types),
                rs -> {
                    String actor = rs.getString(1);
                    out.merge(actor == null ? "unknown" : actor, rs.getLong(2), Long::sum);
                });
        return out;
    }

    // ---- analytics (reference routes/health.py access_analytics) ----

    public List<Map.Entry<String, Long>> topApps(OffsetDateTime from, OffsetDateTime to, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("limit", limit);
        String window = windowClause(from, to, p);
        List<Map.Entry<String, Long>> out = new ArrayList<>();
        jdbc.query("SELECT app_id, count(id) FROM audit_logs WHERE event_type = 'authz.access.granted' "
                + "AND app_id IS NOT NULL" + window + " GROUP BY app_id ORDER BY count(id) DESC, app_id LIMIT :limit", p,
                rs -> {
                    out.add(Map.entry(rs.getString(1), rs.getLong(2)));
                });
        return out;
    }

    public List<Map.Entry<String, Long>> failedLoginsByDay(OffsetDateTime from, OffsetDateTime to, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("limit", limit);
        String window = windowClause(from, to, p);
        List<Map.Entry<String, Long>> out = new ArrayList<>();
        jdbc.query("SELECT to_char(date_trunc('day', created_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS day, count(id) "
                + "FROM audit_logs WHERE event_type = 'auth.login.failed'" + window
                + " GROUP BY 1 ORDER BY 1 DESC LIMIT :limit", p, rs -> {
                    out.add(Map.entry(rs.getString(1), rs.getLong(2)));
                });
        return out;
    }

    public long countType(String eventType, OffsetDateTime from, OffsetDateTime to) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", eventType);
        String window = windowClause(from, to, p);
        Long n = jdbc.queryForObject("SELECT count(id) FROM audit_logs WHERE event_type = :t" + window, p, Long.class);
        return n == null ? 0 : n;
    }

    private static String windowClause(OffsetDateTime from, OffsetDateTime to, MapSqlParameterSource p) {
        StringBuilder sb = new StringBuilder();
        if (from != null) {
            sb.append(" AND created_at >= :from_date");
            p.addValue("from_date", from);
        }
        if (to != null) {
            sb.append(" AND created_at <= :to_date");
            p.addValue("to_date", to);
        }
        return sb.toString();
    }

    private static String where(EventFilter f, MapSqlParameterSource p) {
        List<String> c = new ArrayList<>();
        eq(c, p, "event_type", f.eventType());
        eq(c, p, "category", f.category());
        eq(c, p, "user_id", f.userId());
        eq(c, p, "app_id", f.appId());
        eq(c, p, "realm", f.realm());
        // Against the column, never details: a row cannot hide from its institution's view by
        // carrying a mismatched copy of the value in JSONB.
        eq(c, p, "institution", f.institution());
        eq(c, p, "target", f.target());
        eq(c, p, "severity", f.severity());
        if (f.fromDate() != null) {
            c.add("created_at >= :from_date");
            p.addValue("from_date", f.fromDate());
        }
        if (f.toDate() != null) {
            c.add("created_at <= :to_date");
            p.addValue("to_date", f.toDate());
        }
        return c.isEmpty() ? "" : " WHERE " + String.join(" AND ", c);
    }

    /** Reference semantics: an empty-string filter is "no filter" (Python falsiness). */
    private static void eq(List<String> c, MapSqlParameterSource p, String column, String value) {
        if (value != null && !value.isEmpty()) {
            c.add(column + " = :" + column);
            p.addValue(column, value);
        }
    }

    private AuditEventRead mapRow(ResultSet rs, int i) throws SQLException {
        return new AuditEventRead(
                rs.getObject("id", UUID.class),
                rs.getString("event_type"),
                rs.getString("category"),
                rs.getString("user_id"),
                rs.getString("employee_id"),
                rs.getString("app_id"),
                rs.getString("realm"),
                rs.getString("institution"),
                rs.getString("target"),
                rs.getString("performed_by"),
                rs.getString("ip_address"),
                json.read(rs.getString("details")),
                rs.getString("severity"),
                rs.getString("prev_hash"),
                rs.getString("event_hash"),
                rs.getInt("hash_version"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
