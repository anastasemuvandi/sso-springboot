package rw.gov.risa.sso.notification.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static rw.gov.risa.sso.notification.detector.Ev.min;
import static rw.gov.risa.sso.notification.detector.Ev.ok;

import java.util.List;

import org.junit.jupiter.api.Test;

class ImpossibleTravelRuleTest {

    @Test
    void twoIpsWithinThirtyMinutesFire() {
        List<AuditEvent> ev = List.of(ok("alice", "1.1.1.1", "a", min(0)), ok("alice", "2.2.2.2", "a", min(10)));
        List<DetectionResult> r = ImpossibleTravelRule.evaluate(ev, Ev.all(), 2, 30);
        assertThat(r).hasSize(1);
        assertThat(r.get(0).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(r.get(0).details()).containsEntry("distinct_ips", List.of("1.1.1.1", "2.2.2.2"))
                .containsEntry("login_count", 2);
        assertThat(r.get(0).subjectKey()).isEqualTo("user:alice");
    }

    @Test
    void exactlyThirtyMinutesApartStillFires() {
        List<AuditEvent> ev = List.of(ok("alice", "1.1.1.1", "a", min(0)), ok("alice", "2.2.2.2", "a", min(30)));
        assertThat(ImpossibleTravelRule.evaluate(ev, Ev.all(), 2, 30)).hasSize(1);
    }

    @Test
    void justOverThirtyMinutesDoesNot() {
        List<AuditEvent> ev = List.of(ok("alice", "1.1.1.1", "a", min(0)), ok("alice", "2.2.2.2", "a", min(30.001)));
        assertThat(ImpossibleTravelRule.evaluate(ev, Ev.all(), 2, 30)).isEmpty();
    }

    @Test
    void sameIpTwiceDoesNot() {
        List<AuditEvent> ev = List.of(ok("alice", "1.1.1.1", "a", min(0)), ok("alice", "1.1.1.1", "b", min(5)));
        assertThat(ImpossibleTravelRule.evaluate(ev, Ev.all(), 2, 30)).isEmpty();
    }

    @Test
    void differentUsersDoNot() {
        List<AuditEvent> ev = List.of(ok("alice", "1.1.1.1", "a", min(0)), ok("bob", "2.2.2.2", "a", min(5)));
        assertThat(ImpossibleTravelRule.evaluate(ev, Ev.all(), 2, 30)).isEmpty();
    }

    @Test
    void failuresAndMissingIpsAreIgnored() {
        List<AuditEvent> ev = List.of(ok("alice", "1.1.1.1", "a", min(0)), Ev.fail("alice", "2.2.2.2", min(1)),
                ok("alice", null, "a", min(2)));
        assertThat(ImpossibleTravelRule.evaluate(ev, Ev.all(), 2, 30)).isEmpty();
    }

    @Test
    void onlyANewLoginTriggers() {
        AuditEvent a = ok("alice", "1.1.1.1", "a", min(0));
        AuditEvent b = ok("alice", "2.2.2.2", "a", min(5));
        assertThat(ImpossibleTravelRule.evaluate(List.of(a, b), e -> false, 2, 30)).isEmpty();
        // the new event is the older one: its window [t-30, t] holds only itself
        assertThat(ImpossibleTravelRule.evaluate(List.of(a, b), Ev.only(List.of(a)), 2, 30)).isEmpty();
        assertThat(ImpossibleTravelRule.evaluate(List.of(a, b), Ev.only(List.of(b)), 2, 30)).hasSize(1);
    }

    @Test
    void thresholdIsConfigurable() {
        List<AuditEvent> ev = List.of(ok("alice", "1.1.1.1", "a", min(0)), ok("alice", "2.2.2.2", "a", min(1)),
                ok("alice", "3.3.3.3", "a", min(2)));
        assertThat(ImpossibleTravelRule.evaluate(ev, Ev.all(), 3, 30)).hasSize(1);
        assertThat(ImpossibleTravelRule.evaluate(ev.subList(0, 2), Ev.all(), 3, 30)).isEmpty();
    }
}
