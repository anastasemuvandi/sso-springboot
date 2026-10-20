package rw.gov.risa.sso.notification.repository;

import java.time.Instant;
import java.util.UUID;

public record NotificationRow(
        UUID id,
        UUID alertId,
        String channel,
        String recipient,
        String title,
        String message,
        boolean isRead,
        String status,
        String error,
        Instant sentAt,
        Instant createdAt) {
}
