package rw.gov.risa.sso.notification.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class TimestampsTest {
    @Test
    void parsesZuluOffsetAndNaive() {
        Instant expected = Instant.parse("2026-10-20T10:00:00.123456Z");
        assertThat(Timestamps.parse("2026-10-20T10:00:00.123456Z")).isEqualTo(expected);
        assertThat(Timestamps.parse("2026-10-20T10:00:00.123456+00:00")).isEqualTo(expected);
        assertThat(Timestamps.parse("2026-10-20T12:00:00.123456+02:00")).isEqualTo(expected);
        assertThat(Timestamps.parse("2026-10-20T10:00:00.123456")).isEqualTo(expected);
        assertThat(Timestamps.parse("2026-10-20 10:00:00.123456")).isEqualTo(expected);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> Timestamps.parse("yesterday")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> Timestamps.parse(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
