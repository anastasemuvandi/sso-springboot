package rw.gov.risa.sso.wrapper.audit;

import static net.logstash.logback.argument.StructuredArguments.entries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Two tracks, as in the reference: a structured "audit" log line immediately (always), and a
 * POST to the audit service off the request thread. A failed POST is parked in the Redis list
 * {@value #OUTBOX} and re-sent by {@link #drainOutbox()}, so an outage delays events rather
 * than losing them. Nothing here ever throws into the caller.
 */
@Component
public class HttpAuditEmitter implements AuditEmitter {

    static final String OUTBOX = "audit:outbox";
    private static final int DRAIN_BATCH = 200;

    private static final Logger auditLog = LoggerFactory.getLogger("audit");
    private static final Logger log = LoggerFactory.getLogger(HttpAuditEmitter.class);

    private final RestClient http;
    private final AuditProperties props;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Executor executor;

    public HttpAuditEmitter(AuditProperties props, StringRedisTemplate redis, ObjectMapper mapper,
            @Qualifier("auditExecutor") Executor executor) {
        this.props = props;
        this.redis = redis;
        this.mapper = mapper;
        this.executor = executor;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.timeout());
        rf.setReadTimeout(props.timeout());
        this.http = RestClient.builder().baseUrl(props.serviceUrl()).requestFactory(rf).build();
    }

    @Override
    public void emit(AuditEvent event) {
        try {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("event", event.type().value());
            line.put("outcome", event.outcome());
            line.put("actor", event.actor());
            line.put("client_id", event.clientId());
            line.put("request_ip", event.requestIp());
            line.put("extra", event.extra());
            auditLog.info(event.type().value(), entries(line));

            Map<String, Object> payload = AuditPayloads.toPayload(event);
            executor.execute(() -> deliverOrPark(payload));
        } catch (RuntimeException e) {
            log.error("audit emit failed for {}", event.type().value(), e);
        }
    }

    void deliverOrPark(Map<String, Object> payload) {
        if (send(payload)) {
            return;
        }
        try {
            redis.opsForList().rightPush(OUTBOX, mapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("audit event could not be delivered nor parked: {}", payload.get("event_type"), e);
        }
    }

    boolean send(Map<String, Object> payload) {
        try {
            http.post().uri("/audit/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(AuditProperties.API_KEY_HEADER, props.apiKey())
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            log.warn("audit-service write failed ({}); parking event {}", e.getClass().getSimpleName(),
                    payload.get("event_type"));
            return false;
        }
    }

    /** Re-sends parked events. Stops at the first failure so order is preserved. */
    @Scheduled(fixedDelayString = "${sso.audit.outbox-drain-interval:15s}")
    @SchedulerLock(name = "audit-outbox-drain", lockAtMostFor = "PT2M")
    @SuppressWarnings("unchecked")
    public void drainOutbox() {
        try {
            List<String> batch = redis.opsForList().range(OUTBOX, 0, DRAIN_BATCH - 1);
            if (batch == null) {
                return;
            }
            for (String json : batch) {
                Map<String, Object> payload = mapper.readValue(json, Map.class);
                if (!send(payload)) {
                    return;
                }
                redis.opsForList().leftPop(OUTBOX);
            }
        } catch (Exception e) {
            log.warn("audit outbox drain skipped: {}", e.getMessage());
        }
    }
}
