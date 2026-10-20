package rw.gov.risa.sso.audit.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import io.swagger.v3.oas.annotations.media.Schema;

/** Reference {@code RetentionPolicyCreate}. category null = the default for every category. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(name = "RetentionPolicyCreate")
public record RetentionPolicyCreate(
        @Size(max = 50) @Schema(description = "Event category (AUTHENTICATION, ADMIN, ...); null = default policy")
        String category,
        @NotNull @Min(0) Integer retentionDays,
        @Schema(description = "While true, nothing in this category is ever purged", defaultValue = "false")
        Boolean legalHold) {

    public RetentionPolicyCreate {
        legalHold = legalHold != null && legalHold;
    }
}
