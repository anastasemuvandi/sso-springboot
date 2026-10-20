package rw.gov.risa.sso.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class DateParamsTest {

    private static final OffsetDateTime MIDNIGHT_UTC = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void acceptsTheFormsPydanticAccepts() {
        assertThat(DateParams.parse("d", "2026-10-01T00:00:00Z")).isEqualTo(MIDNIGHT_UTC);
        assertThat(DateParams.parse("d", "2026-10-01T02:00:00+02:00").toInstant()).isEqualTo(MIDNIGHT_UTC.toInstant());
        assertThat(DateParams.parse("d", "2026-10-01T00:00:00")).isEqualTo(MIDNIGHT_UTC);
        assertThat(DateParams.parse("d", "2026-10-01 00:00:00")).isEqualTo(MIDNIGHT_UTC);
        assertThat(DateParams.parse("d", "2026-10-01")).isEqualTo(MIDNIGHT_UTC);
        assertThat(DateParams.parse("d", "2026-10-01T00:00:00.123456+00:00").getNano()).isEqualTo(123_456_000);
    }

    @Test
    void repairsAPlusDecodedAsSpace() {
        assertThat(DateParams.parse("d", "2026-10-01T02:00:00 02:00").toInstant()).isEqualTo(MIDNIGHT_UTC.toInstant());
    }

    @Test
    void blankIsAbsent() {
        assertThat(DateParams.parse("d", null)).isNull();
        assertThat(DateParams.parse("d", " ")).isNull();
    }

    @Test
    void garbageIs422() {
        assertThatThrownBy(() -> DateParams.parse("from_date", "yesterday"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(422));
    }
}
