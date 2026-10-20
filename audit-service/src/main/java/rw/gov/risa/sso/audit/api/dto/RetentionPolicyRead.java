package rw.gov.risa.sso.audit.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import io.swagger.v3.oas.annotations.media.Schema;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(name = "RetentionPolicyRead")
public record RetentionPolicyRead(
        UUID id,
        String category,
        int retentionDays,
        boolean legalHold,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
