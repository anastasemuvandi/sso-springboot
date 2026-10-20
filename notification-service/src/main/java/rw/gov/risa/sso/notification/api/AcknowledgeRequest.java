package rw.gov.risa.sso.notification.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Reference {@code AcknowledgeRequest}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AcknowledgeRequest(@NotBlank @Size(max = 255) String acknowledgedBy) {
}
