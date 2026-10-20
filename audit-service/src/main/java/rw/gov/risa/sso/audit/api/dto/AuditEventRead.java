package rw.gov.risa.sso.audit.api.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import io.swagger.v3.oas.annotations.media.Schema;

import rw.gov.risa.sso.audit.chain.HashChain;

/** A stored audit event, reference {@code AuditEventRead}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(name = "AuditEventRead")
public record AuditEventRead(
        UUID id,
        String eventType,
        String category,
        String userId,
        String employeeId,
        String appId,
        String realm,
        String institution,
        String target,
        String performedBy,
        String ipAddress,
        Map<String, Object> details,
        String severity,
        String prevHash,
        String eventHash,
        int hashVersion,
        OffsetDateTime createdAt) {

    /** The fields covered by the hash, for the writer and the verifier alike. */
    public HashChain.HashInput hashInput() {
        return new HashChain.HashInput(eventType, userId, createdAt, details, performedBy, institution, target,
                appId, ipAddress, severity);
    }
}
