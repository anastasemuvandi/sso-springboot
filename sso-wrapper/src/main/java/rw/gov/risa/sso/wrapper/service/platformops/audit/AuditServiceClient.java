package rw.gov.risa.sso.wrapper.service.platformops.audit;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.audit.AuditProperties;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.service.platformops.PlatformOpsProperties;

/**
 * READ client for the audit-service (port of {@code services/audit_client.py}). The write path is
 * {@code audit.AuditEmitter} (fire-and-forget, never throws); this is its mirror image and deliberately
 * SURFACES failures, so a console shows a real error instead of a silently empty table.
 *
 * <p>The {@code X-Audit-API-Key} is added here, server-side; it never reaches a browser.
 *
 * <p><b>Reuse (institution-admin team):</b> {@code GET /institution-admin/audit} must call
 * {@link #listEventsForInstitution(String, AuditQuery)} (or {@link #exportForInstitution}) with the slug
 * from the caller's resolved {@code InstitutionContext}. Those methods OVERWRITE whatever
 * {@code institution} the query carried, so a request parameter can never widen the view. Do not call
 * {@link #listEvents} from a delegated route.
 *
 * <p>Error mapping (every failure is an {@link ApiException} with the platform envelope; the
 * audit-service's own {@code {error, message, details}} is carried through):
 * <ul>
 *   <li>audit-service 400/404/422 → the same status, the upstream {@code error}/{@code message}/{@code details}
 *       plus {@code details.upstream = "audit-service"};</li>
 *   <li>audit-service 401/403 (the wrapper's key is wrong or missing) → 502 {@code audit_service_error} —
 *       never a 401, which a console would read as "your session expired";</li>
 *   <li>audit-service 5xx or any other status → 502 {@code audit_service_error};</li>
 *   <li>unreachable / timed out → 503 {@code upstream_unavailable}.</li>
 * </ul>
 */
@Component
public class AuditServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AuditServiceClient.class);
    public static final String AUDIT_SERVICE_ERROR = "audit_service_error";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final Duration readTimeout;
    private final Duration exportTimeout;
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public AuditServiceClient(AuditProperties audit, PlatformOpsProperties ops) {
        this(audit.serviceUrl(), audit.apiKey(), ops.auditReadTimeout(), ops.auditExportTimeout());
    }

    public AuditServiceClient(String baseUrl, String apiKey, Duration readTimeout, Duration exportTimeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.readTimeout = readTimeout;
        this.exportTimeout = exportTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** A CSV/PDF export, opened: status and headers known, body still streaming. Close it. */
    public record Export(InputStream body, String contentType, String contentDisposition, Long contentLength)
            implements AutoCloseable {
        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
                // nothing left to release
            }
        }
    }

    // ---- platform reads --------------------------------------------------------------------

    /** {@code GET /audit/events} → {@code {total, limit, offset, items}} (platform: any institution filter). */
    public JsonNode listEvents(AuditQuery query) {
        return getJson("/audit/events", query.params());
    }

    /** {@code GET /audit/integrity/verify} over an optional window. */
    public JsonNode verifyIntegrity(String fromDate, String toDate) {
        Map<String, String> p = new LinkedHashMap<>();
        if (fromDate != null && !fromDate.isBlank()) {
            p.put("from_date", fromDate.strip());
        }
        if (toDate != null && !toDate.isBlank()) {
            p.put("to_date", toDate.strip());
        }
        return getJson("/audit/integrity/verify", p);
    }

    /** {@code GET /audit/retention/policies}. */
    public JsonNode retentionPolicies() {
        return getJson("/audit/retention/policies", Map.of());
    }

    /** {@code GET /audit/export/{csv|pdf}}, streamed. 404 for any other format. */
    public Export export(String format, AuditQuery query) {
        String fmt = format == null ? "" : format.toLowerCase(Locale.ROOT);
        if (!fmt.equals("csv") && !fmt.equals("pdf")) {
            throw ApiException.notFound("Unknown export format");
        }
        HttpResponse<InputStream> res = send("/audit/export/" + fmt, query.params(), exportTimeout,
                HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() >= 400) {
            String body;
            try (InputStream in = res.body()) {
                body = new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
            } catch (IOException e) {
                body = "";
            }
            throw upstreamError(res.statusCode(), body);
        }
        String type = res.headers().firstValue("content-type").orElse("application/octet-stream");
        String disposition = res.headers().firstValue("content-disposition")
                .orElse("attachment; filename=\"audit_export." + fmt + "\"");
        Long length = res.headers().firstValueAsLong("content-length").stream().boxed().findFirst().orElse(null);
        return new Export(res.body(), type, disposition, length);
    }

    // ---- institution-scoped reads (for /institution-admin/audit) -----------------------------

    /**
     * {@code GET /audit/events} pinned to ONE institution: {@code institution} replaces any value in
     * {@code query}. Throws 403 when the slug is blank (a scoped view without a scope is refused, never
     * widened to the whole trail).
     */
    public JsonNode listEventsForInstitution(String institution, AuditQuery query) {
        return listEvents(query.withInstitution(requireInstitution(institution)));
    }

    /** {@link #export} pinned to one institution (same rule as {@link #listEventsForInstitution}). */
    public Export exportForInstitution(String institution, String format, AuditQuery query) {
        return export(format, query.withInstitution(requireInstitution(institution)));
    }

    private static String requireInstitution(String institution) {
        if (institution == null || institution.isBlank()) {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN,
                    "An institution-scoped audit read needs the caller's institution.");
        }
        return institution.strip().toLowerCase(Locale.ROOT);
    }

    // ---- transport -------------------------------------------------------------------------

    private JsonNode getJson(String path, Map<String, String> params) {
        HttpResponse<String> res = send(path, params, readTimeout, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) {
            throw upstreamError(res.statusCode(), res.body());
        }
        try {
            return JSON.readTree(res.body() == null || res.body().isEmpty() ? "null" : res.body());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, AUDIT_SERVICE_ERROR,
                    "The audit-service answered with something that is not JSON.", Map.of("upstream", "audit-service"));
        }
    }

    private <T> HttpResponse<T> send(String path, Map<String, String> params, Duration timeout,
                                     HttpResponse.BodyHandler<T> handler) {
        URI uri = URI.create(baseUrl + path + query(params));
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(timeout).GET()
                .header(AuditProperties.API_KEY_HEADER, apiKey);
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            req.header("X-Correlation-Id", correlationId);
        }
        try {
            return http.send(req.build(), handler);
        } catch (IOException e) {
            log.warn("audit-service unreachable for {}: {}", path, e.toString());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.UPSTREAM_UNAVAILABLE,
                    "The audit-service is unreachable: " + e.getClass().getSimpleName(),
                    Map.of("upstream", "audit-service"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.UPSTREAM_UNAVAILABLE,
                    "Interrupted while calling the audit-service.", Map.of("upstream", "audit-service"));
        }
    }

    static String query(Map<String, String> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringJoiner j = new StringJoiner("&", "?", "");
        params.forEach((k, v) -> j.add(enc(k) + "=" + enc(v)));
        return j.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** The audit-service's error answer as a platform {@link ApiException} (see the class doc). */
    static ApiException upstreamError(int status, String body) {
        String code = null;
        String message = null;
        Map<String, Object> details = new LinkedHashMap<>();
        try {
            JsonNode n = body == null || body.isBlank() ? null : JSON.readTree(body);
            if (n != null && n.isObject()) {
                code = text(n.get("error"));
                message = text(n.get("message"));
                if (message == null) {
                    message = text(n.get("detail"));
                }
                if (n.get("details") != null && n.get("details").isObject()) {
                    details.putAll(JSON.convertValue(n.get("details"), Map.class));
                }
            }
        } catch (IOException ignored) {
            // not JSON: fall through to the raw text
        }
        if (message == null) {
            message = body == null || body.isBlank() ? "HTTP " + status : truncate(body.strip(), 300);
        }
        details.put("upstream", "audit-service");
        details.put("upstream_status", status);
        log.warn("audit-service answered {}: {}", status, message);
        if (status == 400 || status == 404 || status == 422) {
            return new ApiException(HttpStatus.valueOf(status),
                    code != null ? code : status == 404 ? ErrorCodes.NOT_FOUND
                            : status == 422 ? ErrorCodes.VALIDATION_ERROR : ErrorCodes.INVALID_REQUEST,
                    message, details);
        }
        if (status == 401 || status == 403) {
            return new ApiException(HttpStatus.BAD_GATEWAY, AUDIT_SERVICE_ERROR,
                    "The audit-service refused the wrapper's API key (AUDIT_API_KEY): " + message, details);
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, AUDIT_SERVICE_ERROR, "The audit-service failed: " + message,
                details);
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.isTextual() ? n.asText() : n.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
