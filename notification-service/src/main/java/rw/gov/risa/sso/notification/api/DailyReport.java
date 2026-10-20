package rw.gov.risa.sso.notification.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** The daily summary (the content of the reference's 08:00 report email). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DailyReport(
        Instant periodStart,
        Instant periodEnd,
        int total,
        int critical,
        int warning,
        int acknowledged,
        Map<String, Integer> byRule,
        List<AlertRead> latest) {
}
