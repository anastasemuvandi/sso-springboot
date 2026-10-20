package rw.gov.risa.sso.wrapper.service.accessmatrix;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.ImportPreview;

/**
 * The previewed import plans, in Redis ({@code access-matrix-import:<import_id>}, TTL
 * {@link ImportPreview#PLAN_TTL} = 30 minutes; same key as the reference). Redis or 503 — no in-process
 * fallback: the preview and the confirm may be served by different instances, and a plan only one of them
 * can see is a confirm that fails for no reason the admin could understand.
 *
 * <p>The record is THE plan: confirm applies its {@code items}, never the file again.
 */
@Component
public class ImportPlanStore {

    private static final Logger log = LoggerFactory.getLogger(ImportPlanStore.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static final String KEY_PREFIX = "access-matrix-import:";

    /** One planned grant. */
    public record PlannedItem(@JsonProperty("employee_number") String employeeNumber,
                              @JsonProperty("client_id") String clientId) {
    }

    /** What preview stored and confirm applies. */
    public record Plan(
            String institution,
            String actor,
            @JsonProperty("approved_by") String approvedBy,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("file_sha256") String fileSha256,
            @JsonProperty("export_id") String exportId,
            @JsonProperty("exported_at") String exportedAt,
            @JsonProperty("exported_by") String exportedBy,
            List<String> clients,
            List<PlannedItem> items) {
    }

    private final StringRedisTemplate redis;

    public ImportPlanStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String key(String importId) {
        return KEY_PREFIX + importId;
    }

    public void save(String importId, Plan plan) {
        try {
            redis.opsForValue().set(key(importId), JSON.writeValueAsString(plan), ImportPreview.PLAN_TTL);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw unavailable(e);
        }
    }

    public Optional<Plan> get(String importId) {
        String raw;
        try {
            raw = redis.opsForValue().get(key(importId));
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(raw, Plan.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("access matrix import: unreadable plan {}: {}", importId, e.getOriginalMessage());
            return Optional.empty();
        }
    }

    /** Consume the plan: true for exactly one of two concurrent confirms. */
    public boolean consume(String importId) {
        try {
            return Boolean.TRUE.equals(redis.delete(key(importId)));
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    private static ApiException unavailable(Exception e) {
        log.error("access matrix import: plan store unavailable: {}", e.getMessage());
        return ApiException.unavailable(ErrorCodes.UPSTREAM_UNAVAILABLE,
                "Imports are unavailable right now (the plan store cannot be reached). Try again shortly.");
    }
}
