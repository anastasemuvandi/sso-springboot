package rw.gov.risa.sso.notification.detector;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Parses audit-service timestamps. ISO-8601 with an offset or {@code Z} is honoured; a value
 * with no offset is taken as UTC (the audit service stores UTC). The reference dropped any
 * offset and assumed UTC, which mis-placed non-UTC timestamps; this keeps the offset.
 */
public final class Timestamps {
    private Timestamps() {
    }

    public static Instant parse(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("empty timestamp");
        }
        String v = s.trim().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
        }
    }
}
