package rw.gov.risa.sso.notification.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {

    private static final RowMapper<NotificationRow> ROW = (rs, n) -> new NotificationRow(
            rs.getObject("id", UUID.class),
            rs.getObject("alert_id", UUID.class),
            rs.getString("channel"),
            rs.getString("recipient"),
            rs.getString("title"),
            rs.getString("message"),
            rs.getBoolean("is_read"),
            rs.getString("status"),
            rs.getString("error"),
            Sql.instant(rs, "sent_at"),
            Sql.instant(rs, "created_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(NotificationRow r) {
        jdbc.update("""
                INSERT INTO notifications (id, alert_id, channel, recipient, title, message, is_read, status, error,
                                           sent_at, created_at)
                VALUES (:id, :alert, :channel, :recipient, :title, :message, :read, :status, :error, :sent, :created)
                """, new MapSqlParameterSource()
                .addValue("id", r.id())
                .addValue("alert", r.alertId())
                .addValue("channel", r.channel())
                .addValue("recipient", r.recipient())
                .addValue("title", r.title())
                .addValue("message", r.message())
                .addValue("read", r.isRead())
                .addValue("status", r.status())
                .addValue("error", r.error())
                .addValue("sent", Sql.ts(r.sentAt()))
                .addValue("created", Sql.ts(r.createdAt())));
    }

    public List<NotificationRow> list(String recipient, Boolean isRead, String channel, UUID alertId,
                                      int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM notifications WHERE TRUE");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (recipient != null) {
            sql.append(" AND recipient = :recipient");
            p.addValue("recipient", recipient);
        }
        if (isRead != null) {
            sql.append(" AND is_read = :read");
            p.addValue("read", isRead);
        }
        if (channel != null) {
            sql.append(" AND channel = :channel");
            p.addValue("channel", channel);
        }
        if (alertId != null) {
            sql.append(" AND alert_id = :alert");
            p.addValue("alert", alertId);
        }
        sql.append(" ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset");
        p.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), p, ROW);
    }

    public Optional<NotificationRow> findById(UUID id) {
        return jdbc.query("SELECT * FROM notifications WHERE id = :id", Map.of("id", id), ROW).stream().findFirst();
    }

    public Optional<NotificationRow> markRead(UUID id) {
        int n = jdbc.update("UPDATE notifications SET is_read = TRUE WHERE id = :id", Map.of("id", id));
        return n == 0 ? Optional.empty() : findById(id);
    }

    public int markAllRead(String recipient) {
        return jdbc.update("UPDATE notifications SET is_read = TRUE WHERE recipient = :r AND NOT is_read",
                Map.of("r", recipient));
    }

    /** Test/report helper. */
    public int countSince(Instant since) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE created_at >= :s",
                new MapSqlParameterSource("s", Sql.ts(since)), Integer.class);
        return n == null ? 0 : n;
    }
}
