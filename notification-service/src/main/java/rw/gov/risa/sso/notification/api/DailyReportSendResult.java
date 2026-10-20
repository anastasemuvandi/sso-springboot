package rw.gov.risa.sso.notification.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;

/** Outcome of sending the daily report. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DailyReportSendResult(
        @Schema(allowableValues = {"sent", "failed", "skipped"}) String status,
        int alertCount,
        String recipient,
        String error) {
}
