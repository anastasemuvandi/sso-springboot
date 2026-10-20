package rw.gov.risa.sso.notification.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.gov.risa.sso.notification.config.NotificationConfig;
import rw.gov.risa.sso.notification.service.InboxService;

@RestController
@RequestMapping("/notifications")
@Tag(name = "notifications")
@SecurityRequirement(name = NotificationConfig.API_KEY_SCHEME)
public class NotificationsController {

    private final InboxService inbox;

    public NotificationsController(InboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping
    @Operation(summary = "List delivery records (one per channel per recipient per alert), newest first")
    public List<NotificationRead> list(
            @Parameter(description = "Filter by recipient (user email)") @RequestParam(required = false) String recipient,
            @RequestParam(name = "is_read", required = false) Boolean isRead,
            @Parameter(description = "email | inapp | webhook") @RequestParam(required = false) String channel,
            @Parameter(description = "Filter by alert") @RequestParam(name = "alert_id", required = false) UUID alertId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return inbox.list(recipient, isRead, channel, alertId, limit, offset);
    }

    @PutMapping("/read-all")
    @Operation(summary = "Mark all unread notifications for a recipient as read")
    public MarkAllReadResponse markAllRead(
            @Parameter(description = "Mark all unread notifications for this recipient")
            @RequestParam @NotBlank String recipient) {
        return new MarkAllReadResponse(inbox.markAllRead(recipient));
    }

    @PutMapping("/{notification_id}/read")
    @Operation(summary = "Mark one notification as read")
    public NotificationRead markRead(@PathVariable("notification_id") UUID notificationId) {
        return inbox.markRead(notificationId);
    }
}
