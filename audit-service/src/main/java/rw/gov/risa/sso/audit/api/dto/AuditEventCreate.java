package rw.gov.risa.sso.audit.api.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ingest payload ({@code POST /audit/events}), reference {@code AuditEventCreate}. Unknown
 * properties are ignored. {@code institution}/{@code target} are optional: when absent they are
 * lifted from {@code details.institution} and {@code details.target_user} / {@code details.target}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(name = "AuditEventCreate")
public record AuditEventCreate(
        @NotBlank @Size(max = 100)
        @Schema(description = "Dotted event type; the prefix decides the category (auth., authz., admin., lifecycle., session., cert.; anything else is UNKNOWN)",
                example = "auth.login.success", requiredMode = Schema.RequiredMode.REQUIRED)
        String eventType,
        @Size(max = 255) String userId,
        @Size(max = 100) String employeeId,
        @Size(max = 100) String appId,
        @Size(max = 100) String realm,
        @Size(max = 100) @Schema(description = "Institution slug (ADR-0001)") String institution,
        @Size(max = 255) @Schema(description = "What was acted on: user id, client id or role name") String target,
        @Size(max = 255) @Schema(description = "The actor") String performedBy,
        @Size(max = 45) String ipAddress,
        @Schema(description = "Free-form JSON object (wrapper convention: details.outcome carries success/failure/denied)")
        Map<String, Object> details,
        @Schema(defaultValue = "INFO") Severity severity) {

    public AuditEventCreate {
        details = details == null ? Map.of() : details;
        severity = severity == null ? Severity.INFO : severity;
    }

    /** Copy with institution/target replaced. */
    public AuditEventCreate withInstitutionAndTarget(String newInstitution, String newTarget) {
        return new AuditEventCreate(eventType, userId, employeeId, appId, realm, newInstitution, newTarget,
                performedBy, ipAddress, details, severity);
    }

    /** Builder-ish factory for the service's own workers. */
    public static AuditEventCreate system(String eventType, String userId, String institution, String target,
                                          String performedBy, Map<String, Object> details, Severity severity) {
        return new AuditEventCreate(eventType, userId, null, null, null, institution, target, performedBy, null,
                details, severity);
    }
}
