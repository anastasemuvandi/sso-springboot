package rw.gov.risa.sso.notification.detector;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * unusual_hours: WARNING for each successful login outside business hours, by default
 * 06:00–22:00 in Africa/Kigali (UTC+2, no DST, so a fixed offset is exact). A login at
 * {@code businessStart}:00 local is inside; one at {@code businessEnd}:00 local is outside.
 * At most one result per (user, UTC hour) per run, as in the reference.
 */
public final class UnusualHoursRule {

    private static final DateTimeFormatter HOUR_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH")
            .withZone(ZoneOffset.UTC);

    private UnusualHoursRule() {
    }

    public static boolean isOutsideBusinessHours(int localHour, int businessStart, int businessEnd) {
        return localHour < businessStart || localHour >= businessEnd;
    }

    public static List<DetectionResult> evaluate(List<AuditEvent> newEvents, int tzOffsetHours,
                                                 int businessStart, int businessEnd) {
        ZoneOffset offset = ZoneOffset.ofHours(tzOffsetHours);
        List<DetectionResult> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AuditEvent e : newEvents.stream().sorted(Windows.CHRONOLOGICAL).toList()) {
            if (!e.isLoginSuccess()) {
                continue;
            }
            int localHour = e.createdAt().atOffset(offset).getHour();
            if (!isOutsideBusinessHours(localHour, businessStart, businessEnd)) {
                continue;
            }
            String key = e.userId() + "|" + HOUR_KEY.format(e.createdAt());
            if (!seen.add(key)) {
                continue;
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("local_hour", localHour);
            details.put("utc_time", e.createdAt().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC).toString());
            details.put("tz_offset", tzOffsetHours);
            out.add(new DetectionResult(RuleNames.UNUSUAL_HOURS, Severity.WARNING, e.userId(), e.ipAddress(),
                    e.appId(), details, SubjectKeys.of(e.userId(), e.ipAddress())));
        }
        return out;
    }
}
