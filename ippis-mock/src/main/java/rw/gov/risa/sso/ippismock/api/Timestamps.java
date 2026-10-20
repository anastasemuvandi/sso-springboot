package rw.gov.risa.sso.ippismock.api;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** ISO-8601 helpers matching Python's {@code datetime.fromisoformat} / {@code isoformat()} closely enough. */
public final class Timestamps {
    private static final DateTimeFormatter PY_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");

    private Timestamps() {
    }

    /** {@code datetime.now(timezone.utc).isoformat()}: e.g. {@code 2026-10-20T08:00:00.123456+00:00}. */
    public static String now(Clock clock) {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).format(PY_ISO);
    }

    /**
     * Parses an offset date-time, a naive date-time (read as UTC) or a bare date (midnight UTC).
     * A space where the offset's {@code +} should be is repaired: an unencoded {@code +} in a query
     * string arrives as a space.
     */
    public static OffsetDateTime parse(String raw) {
        String s = raw.strip();
        int t = s.indexOf('T');
        if (t > 0 && s.indexOf(' ', t) > 0) {
            s = s.substring(0, s.indexOf(' ', t)) + "+" + s.substring(s.indexOf(' ', t) + 1);
        }
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(s).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        return LocalDate.parse(s).atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
