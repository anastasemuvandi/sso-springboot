package rw.gov.risa.sso.audit.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import rw.gov.risa.sso.audit.api.dto.AuditEventCreate;
import rw.gov.risa.sso.audit.api.dto.AuditEventRead;
import rw.gov.risa.sso.audit.api.dto.Severity;
import rw.gov.risa.sso.audit.repository.AuditEventRepository;
import rw.gov.risa.sso.audit.service.AuditWriter;
import rw.gov.risa.sso.audit.service.IntegrityService;
import rw.gov.risa.sso.audit.workers.AccessReviewWorker;
import rw.gov.risa.sso.audit.workers.CertExpiryWorker;
import rw.gov.risa.sso.audit.workers.HealthCheckWorker;
import rw.gov.risa.sso.audit.workers.KeycloakUserSource.CertUser;
import rw.gov.risa.sso.audit.workers.RetentionWorker;

/** The scheduled workers (reference Celery tasks), driven directly, plus their ShedLock wiring. */
class WorkersIT extends PostgresIT {

    @Autowired
    HealthCheckWorker health;
    @Autowired
    CertExpiryWorker certs;
    @Autowired
    AccessReviewWorker review;
    @Autowired
    RetentionWorker retention;
    @Autowired
    AuditWriter writer;
    @Autowired
    AuditEventRepository repo;
    @Autowired
    IntegrityService integrity;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void healthCheckStoresOneSnapshotPerService() {
        Map<String, String> r = health.run();
        assertThat(r).containsEntry("postgresql", "UP")
                .containsEntry("redis", "UNKNOWN")      // no REDIS_URL configured
                .containsEntry("wrapper", "DOWN")       // nothing listens on port 9
                .containsEntry("ghost", "UNKNOWN");     // no URL configured
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_health WHERE service_name = 'postgresql'",
                Long.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void certExpiryWarnsOncePerThresholdAndAgainAfterRenewal() {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Kigali"));
        String soon = "cert-" + UUID.randomUUID();
        String later = "cert-" + UUID.randomUUID();
        List<CertUser> users = List.of(
                new CertUser(soon, "E1", "a@gov.rw", today.plusDays(25).toString()),
                new CertUser(later, null, null, today.plusDays(75).toString()),
                new CertUser("bad-date", null, null, "not-a-date"));

        CertExpiryWorker.Result first = certs.process(users);
        assertThat(first.warningsWritten()).isEqualTo(4); // 90+60+30 for soon, 90 for later
        assertThat(events(soon)).hasSize(3).allSatisfy(e -> {
            assertThat(e.category()).isEqualTo("CERTIFICATE");
            assertThat(e.severity()).isEqualTo("WARNING");
        });
        assertThat(events(soon)).extracting(e -> e.details().get("threshold")).containsExactlyInAnyOrder(90, 60, 30);

        // Second run: nothing new (the reference crashed here on the unique constraint).
        assertThat(certs.process(users).warningsWritten()).isZero();

        // Renewal: a new expiry date resets the flags.
        List<CertUser> renewed = List.of(new CertUser(later, null, null, today.plusDays(80).toString()));
        assertThat(certs.process(renewed).warningsWritten()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT days_remaining FROM cert_expiry_tracking WHERE user_id = ?",
                Integer.class, later)).isEqualTo(80);
        assertThat(integrity.verify(null, null).valid()).isTrue();
    }

    @Test
    void accessReviewRaisesOneWarningPerInstitutionWithASummary() {
        String inst = "rev" + UUID.randomUUID().toString().substring(0, 6);
        writer.append(new AuditEventCreate("admin.role.assigned", "u1", null, null, null, inst, "u1", "alice",
                null, Map.of(), Severity.INFO));
        writer.append(new AuditEventCreate("admin.role.assigned", "u2", null, null, null, inst, "u2", "alice",
                null, Map.of(), Severity.INFO));
        writer.append(new AuditEventCreate("admin.user.created", "u3", null, null, null, inst, "u3", null,
                null, Map.of(), Severity.INFO));
        writer.append(new AuditEventCreate("auth.login.success", "u3", null, null, null, inst, null, "u3",
                null, Map.of(), Severity.INFO));

        Map<String, Object> out = review.run();
        assertThat((Integer) out.get("institutions")).isGreaterThanOrEqualTo(1);

        List<AuditEventRead> raised = repo.find(new rw.gov.risa.sso.audit.repository.EventFilter(
                AccessReviewWorker.EVENT, null, null, null, null, inst, null, null, null, null), 10, 0);
        assertThat(raised).hasSize(1);
        AuditEventRead e = raised.get(0);
        assertThat(e.target()).isEqualTo(inst);
        assertThat(e.performedBy()).isEqualTo("system:access-review");
        assertThat(e.severity()).isEqualTo("WARNING");
        assertThat(e.category()).isEqualTo("ADMIN");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) e.details().get("summary");
        assertThat(summary).containsEntry("reviewable_total", 3).containsEntry("distinct_actors", 2);
        assertThat((Map<String, Object>) summary.get("events_by_actor")).containsEntry("alice", 2)
                .containsEntry("unknown", 1);
        assertThat((Map<String, Object>) summary.get("events_by_type")).containsEntry("admin.role.assigned", 2);
        assertThat(e.details()).containsEntry("window_days", 92);
    }

    @Test
    void scheduledEntryPointsTakeAShedLock() {
        retention.scheduled();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shedlock WHERE name = 'audit.retention'", Long.class))
                .isEqualTo(1);
        // A second call inside lockAtLeastFor is skipped rather than run twice.
        Long lockedAt = jdbc.queryForObject("SELECT extract(epoch FROM locked_at)::bigint FROM shedlock "
                + "WHERE name = 'audit.retention'", Long.class);
        retention.scheduled();
        assertThat(jdbc.queryForObject("SELECT extract(epoch FROM locked_at)::bigint FROM shedlock "
                + "WHERE name = 'audit.retention'", Long.class)).isEqualTo(lockedAt);
    }

    private List<AuditEventRead> events(String user) {
        return repo.findByUser(user, null, null, 100);
    }
}
