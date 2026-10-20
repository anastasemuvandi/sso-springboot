package rw.gov.risa.sso.notification.channel;

import java.util.Map;
import java.util.UUID;

/** What a channel needs to deliver one alert. */
public record AlertMessage(
        UUID alertId,
        String ruleName,
        String severity,
        String title,
        String message,
        String userId,
        String ipAddress,
        String appId,
        Map<String, Object> details) {
}
