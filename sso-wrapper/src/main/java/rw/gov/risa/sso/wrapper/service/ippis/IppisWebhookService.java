package rw.gov.risa.sso.wrapper.service.ippis;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.ippisperson.EmploymentStatus;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisEmployeeRecord;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPersonViews;
import rw.gov.risa.sso.wrapper.policy.ippisperson.PersonView;
import rw.gov.risa.sso.wrapper.policy.ippisperson.ReconcileOutcome;
import rw.gov.risa.sso.wrapper.service.lifecycle.KeycloakUserGateway;
import rw.gov.risa.sso.wrapper.service.lifecycle.LifecycleService;
import rw.gov.risa.sso.wrapper.service.lifecycle.UserOps;

/**
 * Inbound IPPIS push ({@code api/ippis_webhook.py}, hardened per spec §5.8 / invariant 9).
 *
 * <p>Trust model: off (503) unless IPPIS_WEBHOOK_SECRET is set. IPPIS sends
 * {@code X-IPPIS-Timestamp: <unix seconds>} and
 * {@code X-IPPIS-Signature: sha256=<hex HMAC-SHA256(secret, timestamp + raw body)>} — the timestamp's
 * decimal string immediately followed by the exact received bytes, as {@code ippis-mock} signs.
 * Order of checks: signature (constant-time) → timestamp within IPPIS_WEBHOOK_MAX_SKEW → nonce
 * (Redis {@code SET NX} of the signature, TTL ≥ twice the skew window) so a captured request cannot
 * be replayed, then dispatch: {@code employee.terminated} (or status terminated) → leaver
 * offboarding, otherwise re-hire check + attribute sync. Redis down = fail closed (503).
 */
@Service
public class IppisWebhookService {

    private static final Logger log = LoggerFactory.getLogger(IppisWebhookService.class);

    public static final String SIGNATURE_HEADER = "X-IPPIS-Signature";
    public static final String TIMESTAMP_HEADER = "X-IPPIS-Timestamp";
    public static final String EVENT_ID_HEADER = "X-IPPIS-Event-Id";
    public static final String TRIGGERED_BY = "ippis-webhook";
    public static final String EVENT_TERMINATED = "employee.terminated";
    public static final String EVENT_UPDATED = "employee.updated";

    private final IppisProperties props;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final KeycloakUserGateway gateway;
    private final LifecycleService lifecycle;
    private final IppisReconcileService reconcile;
    private final AuditEmitter audit;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public IppisWebhookService(IppisProperties props, StringRedisTemplate redis, ObjectMapper mapper,
                               KeycloakUserGateway gateway, LifecycleService lifecycle,
                               IppisReconcileService reconcile, AuditEmitter audit) {
        this(props, redis, mapper, gateway, lifecycle, reconcile, audit, Clock.systemUTC());
    }

    IppisWebhookService(IppisProperties props, StringRedisTemplate redis, ObjectMapper mapper,
                        KeycloakUserGateway gateway, LifecycleService lifecycle, IppisReconcileService reconcile,
                        AuditEmitter audit, Clock clock) {
        this.props = props;
        this.redis = redis;
        this.mapper = mapper;
        this.gateway = gateway;
        this.lifecycle = lifecycle;
        this.reconcile = reconcile;
        this.audit = audit;
        this.clock = clock;
    }

    public Map<String, Object> receive(byte[] rawBody, String signature, String timestamp, String requestIp) {
        IppisProperties.Webhook cfg = props.webhook();
        if (!cfg.enabled()) {
            throw ApiException.unavailable(ErrorCodes.NOT_CONFIGURED, "IPPIS webhook is not configured");
        }
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        String ts = timestamp == null ? "" : timestamp.strip();
        if (!verify(cfg.secret(), ts, body, signature)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, WebhookErrors.INVALID_SIGNATURE, "invalid signature");
        }
        long seconds;
        try {
            seconds = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, WebhookErrors.STALE_TIMESTAMP,
                    TIMESTAMP_HEADER + " must be unix seconds");
        }
        long skew = Math.abs(clock.instant().getEpochSecond() - seconds);
        if (skew > cfg.maxSkew().toSeconds()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, WebhookErrors.STALE_TIMESTAMP,
                    "timestamp outside the accepted window of " + cfg.maxSkew().toSeconds() + "s");
        }
        claimNonce(cfg, signature.strip(), cfg.nonceTtl());

        JsonNode event;
        IppisEmployeeRecord employee;
        String type;
        try {
            event = mapper.readTree(body);
            type = event.path("event").asText(null);
            if (!EVENT_TERMINATED.equals(type) && !EVENT_UPDATED.equals(type)) {
                throw new IllegalArgumentException("event must be one of [employee.terminated, employee.updated]");
            }
            if (!event.path("employee").isObject()) {
                throw new IllegalArgumentException("employee is required");
            }
            employee = IppisJson.flatEmployee(event.get("employee"));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.VALIDATION_ERROR,
                    "invalid IPPIS event: " + e.getMessage());
        }
        return dispatch(type, employee, requestIp);
    }

    Map<String, Object> dispatch(String type, IppisEmployeeRecord employee, String requestIp) {
        String attr = props.employeeNumberAttribute();
        PersonView view = IppisPersonViews.viewOfEmployee(employee);
        boolean leaver = EVENT_TERMINATED.equals(type) || employee.status() == EmploymentStatus.TERMINATED;
        Map<String, Object> out = new LinkedHashMap<>();
        try (UserOps ops = gateway.open()) {
            List<UserRepresentation> matches = ops.findByAttribute(attr, employee.employeeNumber());
            if (matches.isEmpty()) {
                log.info("ippis webhook: no Keycloak user for employee {}", employee.employeeNumber());
                audit(type, employee, "no_user", List.of(), requestIp);
                out.put("status", "no_user");
                out.put("employee_number", employee.employeeNumber());
                return out;
            }
            List<Map<String, Object>> results = new ArrayList<>();
            for (UserRepresentation u : matches) {
                ReconcileOutcome o = leaver
                        ? lifecycle.applyLeaver(ops, u, view, TRIGGERED_BY, false)
                        : reconcile.dispatch(ops, u, view, TRIGGERED_BY, false, null);
                results.add(outcomeJson(o));
            }
            audit(type, employee, "ok", results, requestIp);
            out.put("status", "ok");
            out.put("event", type);
            out.put("results", results);
            return out;
        }
    }

    private void audit(String type, IppisEmployeeRecord employee, String status, List<Map<String, Object>> results,
                       String requestIp) {
        audit.emit(AuditEvent.of(AuditEventType.IPPIS_WEBHOOK).success().actor(TRIGGERED_BY).requestIp(requestIp)
                .put("event", type).put("employee_number", employee.employeeNumber()).put("status", status)
                .put("results", results.stream().map(r -> r.get("user_id") + ":" + r.get("status")).toList()).build());
    }

    static Map<String, Object> outcomeJson(ReconcileOutcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", o.userId());
        m.put("status", o.status().wire());
        m.put("detail", o.detail());
        if (o.unmappedInstitution() != null) {
            m.put("unmapped_institution", o.unmappedInstitution());
        }
        if (o.ambiguousInstitution() != null) {
            m.put("ambiguous_institution", o.ambiguousInstitution());
        }
        return m;
    }

    private void claimNonce(IppisProperties.Webhook cfg, String signature, Duration ttl) {
        Boolean fresh;
        try {
            fresh = redis.opsForValue().setIfAbsent(cfg.noncePrefix() + sha256Hex(signature), "1", ttl);
        } catch (RuntimeException e) {
            // Replay protection cannot be checked: fail closed.
            log.error("ippis webhook: nonce store unavailable ({}); refusing the event", e.getClass().getSimpleName());
            throw ApiException.unavailable(ErrorCodes.UPSTREAM_UNAVAILABLE,
                    "Replay protection is unavailable; retry later");
        }
        if (!Boolean.TRUE.equals(fresh)) {
            throw new ApiException(HttpStatus.CONFLICT, WebhookErrors.REPLAYED, "event already received (replay)");
        }
    }

    /** Constant-time comparison of {@code sha256=<hex HMAC(secret, timestamp + body)>}. */
    public static boolean verify(String secret, String timestamp, byte[] body, String header) {
        if (header == null || header.isBlank()) {
            return false;
        }
        String expected = sign(secret, timestamp, body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                header.strip().getBytes(StandardCharsets.UTF_8));
    }

    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Envelope codes of the webhook. */
    public static final class WebhookErrors {
        private WebhookErrors() {
        }

        public static final String INVALID_SIGNATURE = "invalid_signature";
        public static final String STALE_TIMESTAMP = "stale_timestamp";
        public static final String REPLAYED = "replayed";
    }
}
