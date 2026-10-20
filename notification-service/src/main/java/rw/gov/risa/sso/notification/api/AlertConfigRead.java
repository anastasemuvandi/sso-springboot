package rw.gov.risa.sso.notification.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Reference {@code AlertConfigRead}. For a rule with no override row, {@code id} and
 * {@code updated_at} are null (the reference built an unsaved row whose null id failed its own
 * response model); every other field shows the effective value.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AlertConfigRead(
        @Schema(nullable = true) UUID id,
        String ruleName,
        boolean enabled,
        Integer threshold,
        Integer windowMinutes,
        String severity,
        List<String> channels,
        int cooldownMinutes,
        @Schema(nullable = true) Instant updatedAt) {
}
