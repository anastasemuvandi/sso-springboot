package rw.gov.risa.sso.audit.api.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import io.swagger.v3.oas.annotations.media.Schema;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(name = "ServiceHealthRead")
public record ServiceHealthRead(
        UUID id,
        String serviceName,
        String status,
        Integer responseTimeMs,
        Map<String, Object> details,
        OffsetDateTime checkedAt) {
}
