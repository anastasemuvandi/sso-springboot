package rw.gov.risa.sso.notification.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import rw.gov.risa.sso.notification.config.NotificationProperties;
import rw.gov.risa.sso.notification.detector.AuditEvent;
import rw.gov.risa.sso.notification.detector.EventTypes;
import rw.gov.risa.sso.notification.detector.Timestamps;

/**
 * Reads the audit trail over the audit service's HTTP API, exactly as the reference detector
 * worker does: {@code GET {AUDIT_SERVICE_URL}/audit/events} with header
 * {@code X-Audit-API-Key: {AUDIT_API_KEY}}. The response is the audit service's
 * {@code {"total","limit","offset","items":[AuditEventRead...]}} (a bare array or
 * {@code {"events":[...]}} is also accepted, as in the reference).
 *
 * <p>Difference from the reference: it sent {@code since=...}, a parameter the audit service
 * does not have (so it silently got the newest 1000 events). This client uses the real
 * filters {@code from_date}/{@code to_date} and pages with {@code offset}.
 */
@Component
public class AuditClient {

    public static final String API_KEY_HEADER = "X-Audit-API-Key";
    private static final Logger log = LoggerFactory.getLogger(AuditClient.class);

    private final RestClient http;
    private final NotificationProperties.Audit cfg;

    public AuditClient(NotificationProperties props, RestClient.Builder builder) {
        this.cfg = props.audit();
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(cfg.timeoutSeconds()));
        rf.setReadTimeout(Duration.ofSeconds(cfg.timeoutSeconds()));
        this.http = builder.clone()
                .baseUrl(stripSlash(cfg.baseUrl()))
                .requestFactory(rf)
                .defaultHeader(API_KEY_HEADER, cfg.apiKey())
                .build();
    }

    /**
     * All events with {@code fromDate <= created_at <= toDate}. Throws when the audit service
     * cannot be read, so the caller does not advance its watermark past events it never saw.
     */
    public List<AuditEvent> fetchEvents(Instant fromDate, Instant toDate) {
        Map<String, AuditEvent> byId = new LinkedHashMap<>();
        int limit = cfg.pageSize();
        for (int page = 0; page < cfg.maxPages(); page++) {
            int offset = page * limit;
            JsonNode body = http.get()
                    .uri(u -> u.path("/audit/events")
                            .queryParam("from_date", fromDate.toString())
                            .queryParam("to_date", toDate.toString())
                            .queryParam("limit", limit)
                            .queryParam("offset", offset)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            List<JsonNode> items = items(body);
            for (JsonNode n : items) {
                AuditEvent e = toEvent(n);
                if (e != null) {
                    byId.putIfAbsent(e.id(), e);
                }
            }
            if (items.size() < limit) {
                return new ArrayList<>(byId.values());
            }
        }
        log.warn("Audit fetch hit AUDIT_MAX_PAGES ({} x {}); the oldest events of this window were not read",
                cfg.maxPages(), limit);
        return new ArrayList<>(byId.values());
    }

    /**
     * Number of successful logins for (user, app) up to and including {@code upTo}, capped at
     * {@code limit} — the reference's "do we have more than one?" history query.
     */
    public int countLogins(String userId, String appId, Instant upTo, int limit) {
        JsonNode body = http.get()
                .uri((UriBuilder u) -> u.path("/audit/events")
                        .queryParam("user_id", userId)
                        .queryParam("app_id", appId)
                        .queryParam("event_type", EventTypes.AUTH_LOGIN_SUCCESS)
                        .queryParam("to_date", upTo.toString())
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .body(JsonNode.class);
        return items(body).size();
    }

    static List<JsonNode> items(JsonNode body) {
        List<JsonNode> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        JsonNode arr = body;
        if (body.isObject()) {
            arr = body.has("items") ? body.get("items") : body.get("events");
        }
        if (arr != null && arr.isArray()) {
            arr.forEach(out::add);
        }
        return out;
    }

    static AuditEvent toEvent(JsonNode n) {
        String created = text(n, "created_at");
        if (created == null) {
            return null;
        }
        Instant at;
        try {
            at = Timestamps.parse(created);
        } catch (RuntimeException e) {
            log.warn("Skipping audit event with unparseable created_at");
            return null;
        }
        String eventType = text(n, "event_type");
        String user = text(n, "user_id");
        String ip = text(n, "ip_address");
        String app = text(n, "app_id");
        String id = text(n, "id");
        if (id == null) {
            id = "sha256:" + sha256(eventType + "|" + user + "|" + ip + "|" + app + "|" + created);
        }
        return new AuditEvent(id, eventType, user, ip, app, at);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stripSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
