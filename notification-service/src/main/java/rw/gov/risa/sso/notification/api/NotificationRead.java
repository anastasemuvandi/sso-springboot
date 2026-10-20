package rw.gov.risa.sso.notification.api;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import rw.gov.risa.sso.notification.repository.NotificationRow;

/**
 * Reference {@code NotificationRead}, plus {@code error} and {@code sent_at} so a
 * {@code skipped}/{@code failed} delivery says why.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record NotificationRead(
        UUID id,
        UUID alertId,
        @Schema(allowableValues = {"email", "inapp", "webhook"}) String channel,
        String recipient,
        String title,
        String message,
        boolean isRead,
        @Schema(allowableValues = {"pending", "sent", "failed", "skipped"}) String status,
        String error,
        Instant sentAt,
        Instant createdAt) {

    public static NotificationRead of(NotificationRow n) {
        return new NotificationRead(n.id(), n.alertId(), n.channel(), n.recipient(), n.title(), n.message(),
                n.isRead(), n.status(), n.error(), n.sentAt(), n.createdAt());
    }
}
