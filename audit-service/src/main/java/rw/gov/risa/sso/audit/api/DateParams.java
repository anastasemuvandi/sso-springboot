package rw.gov.risa.sso.audit.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses {@code from_date}/{@code to_date} the way the reference (pydantic) accepts them:
 * ISO-8601 with an offset or {@code Z}, a naive date-time (taken as UTC), a space instead of
 * {@code T}, or a bare date (midnight UTC). A {@code +HH:MM} offset whose {@code +} arrived
 * URL-decoded as a space is repaired. Anything else is 422.
 */
public final class DateParams {

    private static final Pattern SPACE_OFFSET = Pattern.compile("^(.*\\d) (\\d{2}:?\\d{2})$");

    private DateParams() {
    }

    public static OffsetDateTime parse(String name, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.strip();
        var m = SPACE_OFFSET.matcher(v);
        if (m.matches() && m.group(1).contains(":")) {
            v = m.group(1) + "+" + m.group(2);
        }
        if (v.length() > 10 && v.charAt(10) == ' ') {
            v = v.substring(0, 10) + "T" + v.substring(11);
        }
        try {
            return OffsetDateTime.parse(v);
        } catch (DateTimeParseException ignored) {
            // try the next form
        }
        try {
            return LocalDateTime.parse(v).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // try the next form
        }
        try {
            return LocalDate.parse(v).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw ApiException.validation("Invalid datetime for " + name,
                    Map.of("fields", Map.of(name, "expected ISO-8601 date-time")));
        }
    }
}
