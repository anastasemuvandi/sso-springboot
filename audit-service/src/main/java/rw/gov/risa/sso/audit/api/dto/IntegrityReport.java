package rw.gov.risa.sso.audit.api.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import io.swagger.v3.oas.annotations.media.Schema;

/** Reference {@code IntegrityReport}, plus {@code tail_matches_head} (null for a window with to_date). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(name = "IntegrityReport")
public record IntegrityReport(
        boolean valid,
        long totalChecked,
        long tamperedCount,
        String firstTamperedId,
        OffsetDateTime firstTamperedAt,
        OffsetDateTime checkedFrom,
        OffsetDateTime checkedTo,
        @Schema(description = "Full-to-now checks only: the newest row is the recorded chain head "
                + "(false means rows were removed from the end of the trail)")
        Boolean tailMatchesHead) {
}
