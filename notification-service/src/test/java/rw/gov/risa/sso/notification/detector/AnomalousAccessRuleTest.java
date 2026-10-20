package rw.gov.risa.sso.notification.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static rw.gov.risa.sso.notification.detector.Ev.min;
import static rw.gov.risa.sso.notification.detector.Ev.ok;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AnomalousAccessRuleTest {

    @Test
    void firstLoginToAnAppFires() {
        List<DetectionResult> r = AnomalousAccessRule.evaluate(List.of(ok("alice", "1.1.1.1", "minagri-x", min(0))),
                Map.of("alice:minagri-x", 1), 1);
        assertThat(r).hasSize(1);
        assertThat(r.get(0).severity()).isEqualTo(Severity.WARNING);
        assertThat(r.get(0).appId()).isEqualTo("minagri-x");
        assertThat(r.get(0).details()).containsEntry("first_access_to_app", "minagri-x");
        assertThat(r.get(0).subjectKey()).isEqualTo("user:alice|app:minagri-x");
    }

    @Test
    void knownPairDoesNotFire() {
        assertThat(AnomalousAccessRule.evaluate(List.of(ok("alice", "1.1.1.1", "x", min(0))),
                Map.of("alice:x", 2), 1)).isEmpty();
    }

    @Test
    void missingHistoryCountsAsZeroAndFires() {
        assertThat(AnomalousAccessRule.evaluate(List.of(ok("alice", "1.1.1.1", "x", min(0))), Map.of(), 1))
                .hasSize(1);
    }

    @Test
    void loginsWithoutUserOrAppAreIgnored() {
        assertThat(AnomalousAccessRule.evaluate(List.of(ok(null, "1.1.1.1", "x", min(0)),
                ok("alice", "1.1.1.1", null, min(0))), Map.of(), 1)).isEmpty();
    }

    @Test
    void onePerPairPerRunAndCandidatesKeepTheEarliest() {
        AuditEvent first = ok("alice", "1.1.1.1", "x", min(0));
        AuditEvent second = ok("alice", "1.1.1.1", "x", min(1));
        assertThat(AnomalousAccessRule.candidates(List.of(second, first))).containsEntry("alice:x", first);
        assertThat(AnomalousAccessRule.evaluate(List.of(first, second), Map.of(), 1)).hasSize(1);
    }

    @Test
    void failuresAreNotAccesses() {
        assertThat(AnomalousAccessRule.evaluate(List.of(Ev.of("auth.login.failed", "a", "1", "x", min(0))),
                Map.of(), 1)).isEmpty();
    }
}
