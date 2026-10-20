package rw.gov.risa.sso.notification.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Service settings. Every value is bound in {@code application.yml} from the reference's
 * environment variable names (NOTIFICATION_API_KEY, AUDIT_SERVICE_URL, SMTP_*, ADMIN_EMAILS,
 * WEBHOOK_URL, TIMEZONE_OFFSET_HOURS, *_COOLDOWN_MINUTES); any of them may also be given as
 * {@code <NAME>_FILE} (see {@link SecretFileEnvironmentPostProcessor}).
 */
@ConfigurationProperties("notification")
public record NotificationProperties(
        String apiKey,
        boolean requireSecrets,
        String adminEmails,
        String webhookUrl,
        int timezoneOffsetHours,
        int businessStartHour,
        int businessEndHour,
        int criticalCooldownMinutes,
        int warningCooldownMinutes,
        Audit audit,
        Smtp smtp,
        Detector detector,
        Report report) {

    public record Audit(String baseUrl, String apiKey, int pageSize, int maxPages, int timeoutSeconds) {
    }

    public record Smtp(String host, int port, String user, String password, String from, boolean useTls,
                       int timeoutSeconds) {
        public boolean configured() {
            return host != null && !host.isBlank();
        }
    }

    public record Detector(boolean enabled, long intervalMs, int initialLookbackMinutes, int overlapMinutes,
                           int settleSeconds) {
    }

    public record Report(boolean enabled, String cron, String zone) {
    }

    /** ADMIN_EMAILS split on commas, trimmed, blanks dropped (reference {@code ADMIN_EMAILS}). */
    public List<String> adminEmailList() {
        if (adminEmails == null) {
            return List.of();
        }
        return Arrays.stream(adminEmails.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public boolean webhookConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
