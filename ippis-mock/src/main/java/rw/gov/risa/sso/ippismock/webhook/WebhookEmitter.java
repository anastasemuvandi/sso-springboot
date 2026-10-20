package rw.gov.risa.sso.ippismock.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import rw.gov.risa.sso.ippismock.config.IppisMockProperties;

/**
 * Opt-in push emission to the SSO's {@code POST /ippis/events}.
 *
 * <p>Enabled only when both {@code SSO_EVENTS_URL} and {@code IPPIS_WEBHOOK_SECRET} are set. Each
 * control-plane write POSTs {@code {"event": "employee.updated|employee.terminated", "employee": <flat record>}}.
 * Signing (SPEC §5.8): {@code X-IPPIS-Timestamp: <unix seconds>} and
 * {@code X-IPPIS-Signature: sha256=<hex HMAC-SHA256(secret, timestamp + raw body)>}, plus a unique
 * {@code X-IPPIS-Event-Id}. {@code IPPIS_MOCK_SIGNATURE_MODE=body} signs the raw body only, which is
 * the Python reference's scheme. Best-effort: a delivery failure is logged and never fails the write
 * (IPPIS is authoritative; the SSO's pull reconcile is the backstop).
 */
@Component
public class WebhookEmitter {
    public static final String SIGNATURE_HEADER = "X-IPPIS-Signature";
    public static final String TIMESTAMP_HEADER = "X-IPPIS-Timestamp";
    public static final String EVENT_ID_HEADER = "X-IPPIS-Event-Id";
    public static final String UPDATED = "employee.updated";
    public static final String TERMINATED = "employee.terminated";

    private static final Logger log = LoggerFactory.getLogger(WebhookEmitter.class);

    private final IppisMockProperties.Webhook config;
    private final ObjectMapper mapper;
    private final RestClient http;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookEmitter(IppisMockProperties properties, ObjectMapper mapper, RestClient.Builder builder) {
        this(properties, mapper, builder, Clock.systemUTC());
    }

    WebhookEmitter(IppisMockProperties properties, ObjectMapper mapper, RestClient.Builder builder, Clock clock) {
        this.config = properties.webhook();
        this.mapper = mapper;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.timeout());
        factory.setReadTimeout(config.timeout());
        this.http = builder.requestFactory(factory).build();
    }

    public static String eventTypeFor(Object status) {
        return "terminated".equals(status) ? TERMINATED : UPDATED;
    }

    public void emit(String eventType, Map<String, Object> employee) {
        if (!config.enabled()) {
            return;
        }
        Object number = employee.get("employee_number");
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", eventType);
            event.put("employee", employee);
            byte[] body = mapper.writeValueAsBytes(event);
            String timestamp = Long.toString(clock.instant().getEpochSecond());
            String signature = sign(config.secret(), signedPayload(timestamp, body));
            var response = http.post()
                    .uri(config.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(SIGNATURE_HEADER, signature)
                    .header(TIMESTAMP_HEADER, timestamp)
                    .header(EVENT_ID_HEADER, UUID.randomUUID().toString())
                    .body(body)
                    .retrieve()
                    .onStatus(s -> true, (req, res) -> { })
                    .toBodilessEntity();
            log.info("ippis-mock: emitted {} for {} -> {}", eventType, number, response.getStatusCode().value());
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("ippis-mock: failed to emit {} for {} ({})", eventType, number, e.getMessage());
        }
    }

    byte[] signedPayload(String timestamp, byte[] body) {
        if ("body".equalsIgnoreCase(config.signatureMode())) {
            return body;
        }
        byte[] ts = timestamp.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[ts.length + body.length];
        System.arraycopy(ts, 0, out, 0, ts.length);
        System.arraycopy(body, 0, out, ts.length, body.length);
        return out;
    }

    public static String sign(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
