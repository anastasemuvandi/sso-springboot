package rw.gov.risa.sso.notification.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static rw.gov.risa.sso.notification.detector.Ev.fail;
import static rw.gov.risa.sso.notification.detector.Ev.min;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BruteForceRuleTest {

    private static List<AuditEvent> failures(String user, String ip, int n, double startMin, double stepMin) {
        List<AuditEvent> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(fail(user, ip, min(startMin + i * stepMin)));
        }
        return out;
    }

    @Test
    void fiveFailuresForOneUserFireCritical() {
        List<AuditEvent> ev = failures("alice", "10.0.0.1", 5, 0, 1);
        List<DetectionResult> r = BruteForceRule.evaluate(ev, Ev.all(), 5, 10);
        assertThat(r).hasSize(1);
        DetectionResult d = r.get(0);
        assertThat(d.ruleName()).isEqualTo("brute_force");
        assertThat(d.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(d.userId()).isEqualTo("alice");
        assertThat(d.ipAddress()).isEqualTo("10.0.0.1");
        assertThat(d.details()).containsEntry("count", 5).containsEntry("cluster", "user");
        assertThat(d.subjectKey()).isEqualTo("user:alice");
    }

    @Test
    void fourFailuresDoNotFire() {
        assertThat(BruteForceRule.evaluate(failures("alice", "10.0.0.1", 4, 0, 1), Ev.all(), 5, 10)).isEmpty();
    }

    @Test
    void windowLowerEdgeIsInclusive() {
        // first at t=0, fifth at exactly t=10 min → all five inside [t-10, t]
        List<AuditEvent> ev = failures("alice", "1.1.1.1", 5, 0, 2.5);
        assertThat(BruteForceRule.evaluate(ev, Ev.all(), 5, 10)).hasSize(1);
    }

    @Test
    void failureJustOutsideWindowIsNotCounted() {
        List<AuditEvent> spread = List.of(fail("bob", "2.2.2.2", min(0)), fail("bob", "2.2.2.2", min(3)),
                fail("bob", "2.2.2.2", min(6)), fail("bob", "2.2.2.2", min(9)), fail("bob", "2.2.2.2", min(10.0001)));
        assertThat(BruteForceRule.evaluate(spread, Ev.all(), 5, 10)).isEmpty();
    }

    @Test
    void credentialStuffingFromOneIpFiresIpCluster() {
        List<AuditEvent> ev = List.of(fail("u1", "6.6.6.6", min(0)), fail("u2", "6.6.6.6", min(1)),
                fail("u3", "6.6.6.6", min(2)), fail("u4", "6.6.6.6", min(3)), fail("u5", "6.6.6.6", min(4)));
        List<DetectionResult> r = BruteForceRule.evaluate(ev, Ev.all(), 5, 10);
        assertThat(r).hasSize(1);
        assertThat(r.get(0).ipAddress()).isEqualTo("6.6.6.6");
        assertThat(r.get(0).subjectKey()).isEqualTo("ip:6.6.6.6");
        assertThat(r.get(0).details()).containsEntry("cluster", "ip").containsEntry("count", 5);
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) r.get(0).details().get("usernames");
        assertThat(names).containsExactly("u1", "u2", "u3", "u4", "u5");
    }

    @Test
    void passwordSprayingFromManyIpsFiresUserCluster() {
        List<AuditEvent> ev = List.of(fail("alice", "1.0.0.1", min(0)), fail("alice", "1.0.0.2", min(1)),
                fail("alice", "1.0.0.3", min(2)), fail("alice", "1.0.0.4", min(3)), fail("alice", "1.0.0.5", min(4)));
        List<DetectionResult> r = BruteForceRule.evaluate(ev, Ev.all(), 5, 10);
        assertThat(r).hasSize(1);
        assertThat(r.get(0).details().get("ips")).asList().hasSize(5);
    }

    @Test
    void singleUserSingleIpIsOneIncidentNotTwo() {
        List<DetectionResult> r = BruteForceRule.evaluate(failures("alice", "10.0.0.1", 6, 0, 1), Ev.all(), 5, 10);
        assertThat(r).extracting(DetectionResult::subjectKey).containsExactly("user:alice");
    }

    @Test
    void unknownUsernameFailuresCountOnlyForTheIp() {
        List<AuditEvent> ev = failures(null, "9.9.9.9", 5, 0, 1);
        List<DetectionResult> r = BruteForceRule.evaluate(ev, Ev.all(), 5, 10);
        assertThat(r).extracting(DetectionResult::subjectKey).containsExactly("ip:9.9.9.9");
        assertThat(r.get(0).userId()).isNull();
    }

    @Test
    void alreadyProcessedEventsAloneNeverFire() {
        List<AuditEvent> ev = failures("alice", "1.1.1.1", 5, 0, 1);
        assertThat(BruteForceRule.evaluate(ev, e -> false, 5, 10)).isEmpty();
    }

    @Test
    void burstStraddlingTwoCyclesFiresWhenTheNewEventCompletesIt() {
        List<AuditEvent> old = failures("alice", "1.1.1.1", 4, 0, 1);
        AuditEvent fifth = fail("alice", "1.1.1.1", min(5));
        List<AuditEvent> all = new ArrayList<>(old);
        all.add(fifth);
        List<DetectionResult> r = BruteForceRule.evaluate(all, Ev.only(List.of(fifth)), 5, 10);
        assertThat(r).hasSize(1);
        assertThat(r.get(0).details()).containsEntry("count", 5);
    }

    @Test
    void reportsTheLargestCountWhenSeveralNewEventsQualify() {
        List<DetectionResult> r = BruteForceRule.evaluate(failures("alice", "1.1.1.1", 8, 0, 1), Ev.all(), 5, 10);
        assertThat(r).hasSize(1);
        assertThat(r.get(0).details()).containsEntry("count", 8);
    }

    @Test
    void legacyEventTypeCountsAndSuccessesDoNot() {
        List<AuditEvent> ev = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ev.add(Ev.of("login_failure", "carol", "3.3.3.3", null, min(i)));
            ev.add(Ev.ok("dave", "4.4.4.4", "app", min(i)));
        }
        assertThat(BruteForceRule.evaluate(ev, Ev.all(), 5, 10))
                .extracting(DetectionResult::userId).containsExactly("carol");
    }

    @Test
    void listsAtMostTenDistinctIps() {
        List<AuditEvent> ev = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            ev.add(fail("alice", "10.0.0." + i, min(i * 0.5)));
        }
        List<DetectionResult> r = BruteForceRule.evaluate(ev, Ev.all(), 5, 10);
        assertThat(r.get(0).details().get("ips")).asList().hasSize(10);
    }

    @Test
    void customThresholdAndWindow() {
        List<AuditEvent> ev = failures("alice", "1.1.1.1", 3, 0, 1);
        assertThat(BruteForceRule.evaluate(ev, Ev.all(), 3, 2)).hasSize(1);
        assertThat(BruteForceRule.evaluate(ev, Ev.all(), 3, 1)).isEmpty();
    }
}
