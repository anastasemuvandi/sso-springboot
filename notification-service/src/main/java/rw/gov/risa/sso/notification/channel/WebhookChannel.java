package rw.gov.risa.sso.notification.channel;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import rw.gov.risa.sso.notification.config.NotificationProperties;
import rw.gov.risa.sso.notification.detector.AlertText;

/**
 * Slack/Teams incoming webhook ({@code WEBHOOK_URL}) with the reference's Slack
 * "attachments" payload. Unset URL → {@code skipped}; non-2xx or I/O error → {@code failed}.
 * The stored recipient is the URL reduced to scheme + host: a Slack webhook URL is itself a
 * secret and must not land in the database or the logs.
 */
@Component
public class WebhookChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(WebhookChannel.class);
    static final String NOT_CONFIGURED = "webhook channel not configured (WEBHOOK_URL is empty)";

    private final String url;
    private final RestClient http;

    public WebhookChannel(NotificationProperties props, RestClient.Builder builder) {
        this.url = props.webhookConfigured() ? props.webhookUrl().trim() : null;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(10));
        rf.setReadTimeout(Duration.ofSeconds(10));
        this.http = builder.clone().requestFactory(rf).build();
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public List<Delivery> deliver(AlertMessage alert, List<String> adminEmails) {
        if (url == null) {
            return List.of(Delivery.skipped(name(), "webhook", NOT_CONFIGURED));
        }
        String recipient = redact(url);
        try {
            http.post().uri(URI.create(url)).contentType(MediaType.APPLICATION_JSON)
                    .body(payload(alert.ruleName(), alert.severity(), alert.details()))
                    .retrieve().toBodilessEntity();
            log.info("Webhook alert sent: {} {}", alert.severity(), alert.ruleName());
            return List.of(Delivery.sent(name(), recipient));
        } catch (Exception e) {
            log.error("Webhook delivery failed: {}", e.getClass().getSimpleName());
            return List.of(Delivery.failed(name(), recipient, e));
        }
    }

    /** Reference {@code send_webhook} payload (Slack attachments; Teams renders it too). */
    public static Map<String, Object> payload(String rule, String severity, Map<String, Object> details) {
        String emoji = switch (severity) {
            case "CRITICAL" -> ":red_circle:";
            case "WARNING" -> ":large_yellow_circle:";
            case "INFO" -> ":large_blue_circle:";
            default -> ":bell:";
        };
        String color = EmailChannel.severityColor(severity);
        List<Map<String, Object>> fields = (details == null ? Map.<String, Object>of() : details).entrySet().stream()
                .map(e -> {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("title", e.getKey());
                    f.put("value", String.valueOf(e.getValue()));
                    f.put("short", true);
                    return f;
                }).toList();
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("color", color);
        attachment.put("title", emoji + " [" + severity + "] SSO Alert — " + AlertText.humanize(rule));
        attachment.put("fields", fields);
        attachment.put("footer", "Government of Rwanda SSO");
        return Map.of("attachments", List.of(attachment));
    }

    static String redact(String url) {
        try {
            URI u = URI.create(url);
            String port = u.getPort() > 0 ? ":" + u.getPort() : "";
            return u.getScheme() + "://" + u.getHost() + port + "/…";
        } catch (RuntimeException e) {
            return "webhook";
        }
    }
}
