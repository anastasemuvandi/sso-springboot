package rw.gov.risa.sso.audit.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CertExpiryRepository {

    public record Tracking(String userId, String employeeId, String email, LocalDate certExpiry,
                           Integer daysRemaining, boolean notified30, boolean notified60, boolean notified90) {
    }

    private static final RowMapper<Tracking> MAPPER = (rs, i) -> new Tracking(rs.getString("user_id"),
            rs.getString("employee_id"), rs.getString("email"), rs.getObject("cert_expiry", LocalDate.class),
            (Integer) rs.getObject("days_remaining"), rs.getBoolean("notified_30"), rs.getBoolean("notified_60"),
            rs.getBoolean("notified_90"));

    private final NamedParameterJdbcTemplate jdbc;

    public CertExpiryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Tracking> findForUpdate(String userId) {
        return jdbc.query("SELECT * FROM cert_expiry_tracking WHERE user_id = :u FOR UPDATE",
                new MapSqlParameterSource("u", userId), MAPPER).stream().findFirst();
    }

    public void save(Tracking t) {
        jdbc.update("INSERT INTO cert_expiry_tracking (user_id, employee_id, email, cert_expiry, days_remaining, "
                        + "notified_30, notified_60, notified_90, updated_at) VALUES (:u, :e, :m, :x, :d, :n30, :n60, :n90, now()) "
                        + "ON CONFLICT (user_id) DO UPDATE SET employee_id = EXCLUDED.employee_id, email = EXCLUDED.email, "
                        + "cert_expiry = EXCLUDED.cert_expiry, days_remaining = EXCLUDED.days_remaining, "
                        + "notified_30 = EXCLUDED.notified_30, notified_60 = EXCLUDED.notified_60, "
                        + "notified_90 = EXCLUDED.notified_90, updated_at = now()",
                new MapSqlParameterSource("u", t.userId()).addValue("e", t.employeeId()).addValue("m", t.email())
                        .addValue("x", t.certExpiry()).addValue("d", t.daysRemaining())
                        .addValue("n30", t.notified30()).addValue("n60", t.notified60()).addValue("n90", t.notified90()));
    }

    public List<Tracking> expiringOnOrBefore(LocalDate cutoff) {
        return jdbc.query("SELECT * FROM cert_expiry_tracking WHERE cert_expiry <= :c ORDER BY cert_expiry ASC, user_id",
                new MapSqlParameterSource("c", cutoff), MAPPER);
    }
}
