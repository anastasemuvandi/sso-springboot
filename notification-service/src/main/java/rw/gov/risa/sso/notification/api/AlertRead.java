package rw.gov.risa.sso.notification.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import rw.gov.risa.sso.notification.repository.AlertRow;

/** Reference {@code AlertRead}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AlertRead(
        UUID id,
        String ruleName,
        String severity,
        String userId,
        String ipAddress,
        String appId,
        Map<String, Object> details,
        boolean acknowledged,
        String acknowledgedBy,
        Instant acknowledgedAt,
        Instant createdAt) {

    public static AlertRead of(AlertRow a) {
        return new AlertRead(a.id(), a.ruleName(), a.severity(), a.userId(), a.ipAddress(), a.appId(), a.details(),
                a.acknowledged(), a.acknowledgedBy(), a.acknowledgedAt(), a.createdAt());
    }
}
