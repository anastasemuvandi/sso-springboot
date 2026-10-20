package rw.gov.risa.sso.notification.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AlertConfigRow(
        UUID id,
        String ruleName,
        boolean enabled,
        Integer threshold,
        Integer windowMinutes,
        String severity,
        List<String> channels,
        Integer cooldownMinutes,
        Instant updatedAt) {
}
