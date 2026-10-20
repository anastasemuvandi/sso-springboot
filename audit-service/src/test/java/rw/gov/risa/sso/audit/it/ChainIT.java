package rw.gov.risa.sso.audit.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import rw.gov.risa.sso.audit.api.dto.AuditEventCreate;
import rw.gov.risa.sso.audit.api.dto.AuditEventRead;
import rw.gov.risa.sso.audit.api.dto.IntegrityReport;
import rw.gov.risa.sso.audit.api.dto.Severity;
import rw.gov.risa.sso.audit.chain.EventCategories;
import rw.gov.risa.sso.audit.chain.HashChain;
import rw.gov.risa.sso.audit.repository.AuditEventRepository;
import rw.gov.risa.sso.audit.service.AuditWriter;
import rw.gov.risa.sso.audit.service.IntegrityService;

/** Writer, chain head lock, append-only trigger and verifier against a real Postgres. */
class ChainIT extends PostgresIT {

    @Autowired
    AuditWriter writer;
    @Autowired
    IntegrityService integrity;
    @Autowired
    AuditEventRepository repo;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;

    private static AuditEventCreate event(String type, String user, Map<String, Object> details) {
        return new AuditEventCreate(type, user, null, "minagri-kubaka-web", "government-internal", "minagri", user,
                user, "10.0.0.1", details, Severity.INFO);
    }

    private long rows() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class);
    }

    @Test
    void sequentialEventsLinkAndVerify() {
        AuditEventRead a = writer.append(event("auth.login.success", "chain-user", Map.of("seq", 1)));
        AuditEventRead b = writer.append(event("authz.access.granted", "chain-user", Map.of("seq", 2)));
        AuditEventRead c = writer.append(event("auth.logout", "chain-user", Map.of("seq", 3)));
        assertThat(b.prevHash()).isEqualTo(a.eventHash());
        assertThat(c.prevHash()).isEqualTo(b.eventHash());
        assertThat(List.of(a, b, c)).allSatisfy(e -> {
            assertThat(e.hashVersion()).isEqualTo(HashChain.CURRENT_HASH_VERSION);
            assertThat(HashChain.compute(e.hashInput(), e.prevHash(), e.hashVersion())).isEqualTo(e.eventHash());
        });
        assertThat(b.createdAt()).isAfter(a.createdAt());
        assertThat(a.category()).isEqualTo("AUTHENTICATION");
        assertThat(repo.headHash()).isEqualTo(c.eventHash());

        IntegrityReport r = integrity.verify(null, null);
        assertThat(r.valid()).isTrue();
        assertThat(r.tamperedCount()).isZero();
        assertThat(r.tailMatchesHead()).isTrue();
    }

    @Test
    void storedRowRehashesIdenticallyAfterJsonbRoundTrip() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("z", 1e2);
        d.put("a", List.of(0.1, 12345678901234567890.0, "é😀"));
        d.put("n", null);
        AuditEventRead w = writer.append(event("admin.user.created", "json-user", d));
        AuditEventRead stored = repo.findByUser("json-user", null, null, 1).get(0);
        assertThat(stored.eventHash()).isEqualTo(w.eventHash());
        assertThat(HashChain.compute(stored.hashInput(), stored.prevHash(), stored.hashVersion()))
                .isEqualTo(stored.eventHash());
    }

    @Test
    void concurrentWritersNeverForkTheChain() throws Exception {
        int threads = 8;
        int perThread = 25;
        long before = rows();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<AuditEventRead>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            futures.add(pool.submit(() -> {
                go.await();
                List<AuditEventRead> mine = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    mine.add(writer.append(event("auth.login.success", "conc-" + id, Map.of("i", i))));
                }
                return mine;
            }));
        }
        go.countDown();
        List<AuditEventRead> all = new ArrayList<>();
        for (Future<List<AuditEventRead>> f : futures) {
            all.addAll(f.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(all).hasSize(threads * perThread);
        assertThat(rows()).isEqualTo(before + threads * perThread);
        Set<String> prevs = new HashSet<>();
        all.forEach(e -> prevs.add(e.prevHash()));
        assertThat(prevs).as("every event links to a distinct predecessor").hasSize(threads * perThread);
        Long dupPrev = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT prev_hash FROM audit_logs GROUP BY prev_hash HAVING count(*) > 1) x",
                Long.class);
        assertThat(dupPrev).isZero();
        assertThat(integrity.verify(null, null).valid()).isTrue();
    }

    @Test
    void triggerRefusesUpdateDeleteAndTruncate() {
        AuditEventRead e = writer.append(event("auth.login.failed", "trigger-user", Map.of()));
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_logs SET performed_by = 'attacker' WHERE id = ?", e.id()))
                .isInstanceOf(DataIntegrityViolationException.class).satisfies(ex -> assertThat(root(ex)).contains("append-only"));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs WHERE id = ?", e.id()))
                .isInstanceOf(DataIntegrityViolationException.class).satisfies(ex -> assertThat(root(ex)).contains("append-only"));
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_logs"))
                .isInstanceOf(DataAccessException.class).satisfies(ex -> assertThat(root(ex)).contains("TRUNCATE refused"));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_chain_head"))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT performed_by FROM audit_logs WHERE id = ?", String.class, e.id()))
                .isEqualTo("trigger-user");
    }

    @Test
    void purgeFlagIsTransactionLocalAndLeavesAHashLink() {
        AuditEventRead e = writer.append(event("auth.login.failed", "purge-user", Map.of()));
        Integer deleted = tx.execute(s -> {
            repo.enableRetentionPurge();
            return jdbc.update("DELETE FROM audit_logs WHERE id = ?", e.id());
        });
        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT prev_hash FROM audit_purged_links WHERE event_hash = ?", String.class,
                e.eventHash())).isEqualTo(e.prevHash());
        // The flag died with its transaction.
        AuditEventRead other = writer.append(event("auth.login.failed", "purge-user-2", Map.of()));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs WHERE id = ?", other.id()))
                .satisfies(ex -> assertThat(root(ex)).contains("append-only"));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_purged_links WHERE event_hash = ?", e.eventHash()))
                .isInstanceOf(DataAccessException.class);
        // The purged row is bridged: the chain still verifies.
        assertThat(integrity.verify(null, null).valid()).isTrue();
    }

    @Test
    void editedRowIsReportedAndRestoringItHeals() {
        AuditEventRead victim = writer.append(event("admin.role.assigned", "tamper-user", Map.of("role", "x")));
        writer.append(event("admin.role.assigned", "tamper-user-2", Map.of()));
        asSuperuser("UPDATE audit_logs SET performed_by = 'attacker' WHERE id = '" + victim.id() + "'");
        try {
            IntegrityReport r = integrity.verify(null, null);
            assertThat(r.valid()).isFalse();
            assertThat(r.tamperedCount()).isEqualTo(1);
            assertThat(r.firstTamperedId()).isEqualTo(victim.id().toString());
            assertThat(r.firstTamperedAt()).isEqualTo(victim.createdAt());
        } finally {
            asSuperuser("UPDATE audit_logs SET performed_by = 'tamper-user' WHERE id = '" + victim.id() + "'");
        }
        assertThat(integrity.verify(null, null).valid()).isTrue();
    }

    @Test
    void deletingTheNewestRowIsCaughtByTheHeadCheck() {
        AuditEventRead last = writer.append(event("auth.logout", "tail-user", Map.of()));
        asSuperuser("DELETE FROM audit_logs WHERE id = '" + last.id() + "'");
        try {
            IntegrityReport r = integrity.verify(null, null);
            assertThat(r.tamperedCount()).isZero();
            assertThat(r.tailMatchesHead()).isFalse();
            assertThat(r.valid()).isFalse();
        } finally {
            repo.insert(last);
        }
        assertThat(integrity.verify(null, null).valid()).isTrue();
    }

    @Test
    void legacyV1RowsWrittenByThePythonServiceStillVerify() {
        tx.executeWithoutResult(s -> {
            AuditEventRepository.ChainHead head = repo.lockHead();
            OffsetDateTime base = head.lastCreatedAt() == null ? OffsetDateTime.now() : head.lastCreatedAt();
            OffsetDateTime at = HashChain.toMicros(base.plusNanos(1000));
            Map<String, Object> details = Map.of("v", 1);
            var input = new HashChain.HashInput("auth.logout", "legacy-user", at, details, "someone", null, null,
                    null, null, "INFO");
            String hash = HashChain.compute(input, head.lastHash(), 1);
            repo.insert(new AuditEventRead(UUID.randomUUID(), "auth.logout", EventCategories.infer("auth.logout"),
                    "legacy-user", null, null, null, null, null, "someone", null, details, "INFO", head.lastHash(),
                    hash, 1, at));
            repo.advanceHead(hash, at);
        });
        AuditEventRead after = writer.append(event("auth.login.success", "legacy-user", Map.of("v", 2)));
        assertThat(after.createdAt()).isAfter(OffsetDateTime.now().minusMinutes(5));
        assertThat(integrity.verify(null, null).valid()).isTrue();
    }

    @Test
    void windowedVerificationAnchorsOnThePreviousRow() {
        writer.append(event("auth.login.success", "window-user", Map.of()));
        AuditEventRead mid = writer.append(event("auth.login.success", "window-user", Map.of()));
        writer.append(event("auth.login.success", "window-user", Map.of()));
        IntegrityReport r = integrity.verify(mid.createdAt(), null);
        assertThat(r.valid()).isTrue();
        assertThat(r.totalChecked()).isGreaterThanOrEqualTo(2);
        assertThat(r.checkedFrom()).isEqualTo(mid.createdAt());
        IntegrityReport bounded = integrity.verify(mid.createdAt(), mid.createdAt());
        assertThat(bounded.totalChecked()).isEqualTo(1);
        assertThat(bounded.valid()).isTrue();
        assertThat(bounded.tailMatchesHead()).isNull();
    }

    private static String root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return String.valueOf(c.getMessage());
    }

    /** Only a superuser with the trigger disabled can do this; it simulates direct DB tampering. */
    private void asSuperuser(String sql) {
        tx.executeWithoutResult(s -> {
            jdbc.execute("ALTER TABLE audit_logs DISABLE TRIGGER trg_audit_logs_append_only");
            jdbc.execute(sql);
            jdbc.execute("ALTER TABLE audit_logs ENABLE TRIGGER trg_audit_logs_append_only");
        });
    }
}
