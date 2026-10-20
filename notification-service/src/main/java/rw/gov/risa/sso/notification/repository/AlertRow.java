package rw.gov.risa.sso.notification.repository;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AlertRow(
        UUID id,
        String ruleName,
        String severity,
        String userId,
        String ipAddress,
        String appId,
        String subjectKey,
        Map<String, Object> details,
        boolean acknowledged,
        String acknowledgedBy,
        Instant acknowledgedAt,
        Instant createdAt) {
}
