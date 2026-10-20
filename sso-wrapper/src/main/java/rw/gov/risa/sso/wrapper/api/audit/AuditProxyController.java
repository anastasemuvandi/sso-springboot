package rw.gov.risa.sso.wrapper.api.audit;

import java.io.InputStream;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.service.platformops.audit.AuditQuery;
import rw.gov.risa.sso.wrapper.service.platformops.audit.AuditServiceClient;
import rw.gov.risa.sso.wrapper.service.platformops.audit.AuditServiceClient.Export;

/**
 * {@code /audit/*}: the read-only proxy to the audit-service (admin tier). The caller proves who they
 * are with their SSO bearer; the wrapper adds the audit-service API key server-side. Upstream failures
 * are surfaced (see {@link AuditServiceClient}), never turned into empty tables.
 */
@RestController
@RequestMapping("/audit")
@Tag(name = "Audit")
public class AuditProxyController {

    private final AuditServiceClient audit;

    public AuditProxyController(AuditServiceClient audit) {
        this.audit = audit;
    }

    @GetMapping("/events")
    @RateLimit("120/minute")
    @Operation(summary = "Paginated, filterable audit events from the audit-service")
    public JsonNode events(
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(required = false) String category,
            @RequestParam(name = "user_id", required = false) String userId,
            @RequestParam(name = "app_id", required = false) String appId,
            @RequestParam(required = false) String realm,
            @Parameter(description = "Institution slug. Platform admins may filter to any institution; a local "
                    + "admin uses GET /institution-admin/audit, which pins this server-side.")
            @RequestParam(required = false) String institution,
            @Parameter(description = "What was acted on — user id, client id, or role name.")
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String severity,
            @RequestParam(name = "from_date", required = false) String fromDate,
            @RequestParam(name = "to_date", required = false) String toDate,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return audit.listEvents(new AuditQuery(eventType, category, userId, appId, realm, institution, target,
                severity, fromDate, toDate, limit, offset));
    }

    @GetMapping("/integrity/verify")
    @RateLimit("20/minute")
    @Operation(summary = "Verify the audit log's hash chain over an optional date window")
    public JsonNode verify(@RequestParam(name = "from_date", required = false) String fromDate,
                           @RequestParam(name = "to_date", required = false) String toDate) {
        return audit.verifyIntegrity(fromDate, toDate);
    }

    @GetMapping("/retention/policies")
    @RateLimit("60/minute")
    @Operation(summary = "Configured retention policies per event category (read-only)")
    public JsonNode retentionPolicies() {
        return audit.retentionPolicies();
    }

    @GetMapping("/export/{fmt}")
    @RateLimit("10/minute")
    @Operation(summary = "Stream a CSV or PDF export straight through from the audit-service")
    public ResponseEntity<StreamingResponseBody> export(
            @PathVariable("fmt") String fmt,
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(required = false) String category,
            @RequestParam(name = "user_id", required = false) String userId,
            @RequestParam(name = "app_id", required = false) String appId,
            @RequestParam(required = false) String realm,
            @RequestParam(required = false) String institution,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String severity,
            @RequestParam(name = "from_date", required = false) String fromDate,
            @RequestParam(name = "to_date", required = false) String toDate,
            @RequestParam(required = false) @Min(1) @Max(50_000) Integer limit) {
        Export export = audit.export(fmt, new AuditQuery(eventType, category, userId, appId, realm, institution,
                target, severity, fromDate, toDate, limit, null));
        return streamed(export);
    }

    /** Headers from the audit-service (type, disposition, length) and its body, copied as it arrives. */
    public static ResponseEntity<StreamingResponseBody> streamed(Export export) {
        StreamingResponseBody body = out -> {
            try (export; InputStream in = export.body()) {
                in.transferTo(out);
            }
        };
        ResponseEntity.BodyBuilder r = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(export.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, export.contentDisposition());
        if (export.contentLength() != null && export.contentLength() >= 0) {
            r.contentLength(export.contentLength());
        }
        return r.body(body);
    }
}
