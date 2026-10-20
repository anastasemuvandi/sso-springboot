package rw.gov.risa.sso.notification.detector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class CooldownPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    @Test
    void noPreviousAlertNeverSuppresses() {
        assertThat(CooldownPolicy.isSuppressed(null, NOW, 5)).isFalse();
    }

    @Test
    void alertInsideCooldownSuppresses() {
        assertThat(CooldownPolicy.isSuppressed(NOW.minus(Duration.ofMinutes(4)), NOW, 5)).isTrue();
    }

    @Test
    void alertExactlyAtCooldownEdgeStillSuppresses() {
        assertThat(CooldownPolicy.isSuppressed(NOW.minus(Duration.ofMinutes(5)), NOW, 5)).isTrue();
    }

    @Test
    void alertJustBeforeCooldownEdgeDoesNot() {
        assertThat(CooldownPolicy.isSuppressed(NOW.minus(Duration.ofMinutes(5)).minusNanos(1), NOW, 5)).isFalse();
    }

    @Test
    void perRuleCooldownsDiffer() {
        Instant twoHoursAgo = NOW.minus(Duration.ofHours(2));
        assertThat(CooldownPolicy.isSuppressed(twoHoursAgo, NOW, 60)).isFalse();     // unusual_hours
        assertThat(CooldownPolicy.isSuppressed(twoHoursAgo, NOW, 1440)).isTrue();    // anomalous_access
    }

    @Test
    void zeroAndNegativeCooldownOnlySuppressSameInstant() {
        assertThat(CooldownPolicy.isSuppressed(NOW, NOW, 0)).isTrue();
        assertThat(CooldownPolicy.isSuppressed(NOW.minusMillis(1), NOW, 0)).isFalse();
        assertThat(CooldownPolicy.since(NOW, -3)).isEqualTo(NOW);
    }
}
