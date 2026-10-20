package rw.gov.risa.sso.audit.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import rw.gov.risa.sso.audit.api.dto.RetentionPolicyRead;

@Repository
public class RetentionPolicyRepository {

    private static final RowMapper<RetentionPolicyRead> MAPPER = (rs, i) -> new RetentionPolicyRead(
            rs.getObject("id", UUID.class), rs.getString("category"), rs.getInt("retention_days"),
            rs.getBoolean("legal_hold"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;

    public RetentionPolicyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Default (NULL) policy first, then by category, as the reference. */
    public List<RetentionPolicyRead> findAll() {
        return jdbc.query("SELECT * FROM retention_policies ORDER BY category ASC NULLS FIRST", MAPPER);
    }

    /** Locks the row for the duration of the transaction (upsert read-modify-write). */
    public Optional<RetentionPolicyRead> findForUpdate(String category) {
        return jdbc.query("SELECT * FROM retention_policies WHERE category IS NOT DISTINCT FROM :c FOR UPDATE",
                new MapSqlParameterSource("c", category), MAPPER).stream().findFirst();
    }

    /**
     * Insert-or-update keyed on category, NULL included (the unique index is NULLS NOT DISTINCT, so
     * the reference's "a second default row on every save" cannot happen).
     */
    public RetentionPolicyRead upsert(String category, int days, boolean legalHold) {
        return jdbc.queryForObject("INSERT INTO retention_policies (category, retention_days, legal_hold) "
                        + "VALUES (:c, :d, :h) ON CONFLICT (category) DO UPDATE SET retention_days = EXCLUDED.retention_days, "
                        + "legal_hold = EXCLUDED.legal_hold, updated_at = now() RETURNING *",
                new MapSqlParameterSource("c", category).addValue("d", days).addValue("h", legalHold), MAPPER);
    }
}
