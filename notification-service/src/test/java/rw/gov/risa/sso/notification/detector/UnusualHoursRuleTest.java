package rw.gov.risa.sso.notification.detector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UnusualHoursRuleTest {

    private static List<DetectionResult> at(String utc) {
        return UnusualHoursRule.evaluate(List.of(Ev.ok("alice", "1.1.1.1", "app", Instant.parse(utc))), 2, 6, 22);
    }

    @ParameterizedTest(name = "{0} UTC (Kigali {1}) → fires={2}")
    @CsvSource({
            "2026-10-20T03:59:59Z, 05:59:59, true",
            "2026-10-20T04:00:00Z, 06:00:00, false",
            "2026-10-20T10:00:00Z, 12:00:00, false",
            "2026-10-20T19:59:59Z, 21:59:59, false",
            "2026-10-20T20:00:00Z, 22:00:00, true",
            "2026-10-20T21:59:59Z, 23:59:59, true",
            "2026-10-20T22:00:00Z, 00:00:00 next day, true",
            "2026-10-19T23:30:00Z, 01:30:00, true",
    })
    void businessHoursBoundariesInKigali(String utc, String kigali, boolean fires) {
        assertThat(at(utc)).hasSize(fires ? 1 : 0);
    }

    @Test
    void detailsCarryLocalHourUtcTimeAndOffset() {
        DetectionResult d = at("2026-10-20T20:15:00Z").get(0);
        assertThat(d.severity()).isEqualTo(Severity.WARNING);
        assertThat(d.details()).containsEntry("local_hour", 22).containsEntry("tz_offset", 2)
                .containsEntry("utc_time", "2026-10-20T20:15Z");
        assertThat(d.userId()).isEqualTo("alice");
        assertThat(d.appId()).isEqualTo("app");
        assertThat(d.ipAddress()).isEqualTo("1.1.1.1");
    }

    @Test
    void onePerUserPerUtcHour() {
        List<AuditEvent> ev = List.of(
                Ev.ok("alice", "1.1.1.1", "a", Instant.parse("2026-10-20T21:05:00Z")),
                Ev.ok("alice", "1.1.1.1", "a", Instant.parse("2026-10-20T21:55:00Z")),
                Ev.ok("alice", "1.1.1.1", "a", Instant.parse("2026-10-20T22:05:00Z")),
                Ev.ok("bob", "1.1.1.2", "a", Instant.parse("2026-10-20T21:10:00Z")));
        assertThat(UnusualHoursRule.evaluate(ev, 2, 6, 22)).hasSize(3);
    }

    @Test
    void failuresAreIgnored() {
        assertThat(UnusualHoursRule.evaluate(
                List.of(Ev.fail("alice", "1.1.1.1", Instant.parse("2026-10-20T01:00:00Z"))), 2, 6, 22)).isEmpty();
    }

    @Test
    void offsetIsConfigurable() {
        // 05:00 UTC is 07:00 in Kigali (inside) but 05:00 at offset 0 (outside)
        List<AuditEvent> ev = List.of(Ev.ok("alice", "1.1.1.1", "a", Instant.parse("2026-10-20T05:00:00Z")));
        assertThat(UnusualHoursRule.evaluate(ev, 2, 6, 22)).isEmpty();
        assertThat(UnusualHoursRule.evaluate(ev, 0, 6, 22)).hasSize(1);
    }

    @Test
    void legacySuccessTypeCounts() {
        assertThat(UnusualHoursRule.evaluate(List.of(Ev.of("login_success", "a", null, null,
                Instant.parse("2026-10-20T01:00:00Z"))), 2, 6, 22)).hasSize(1);
    }
}
