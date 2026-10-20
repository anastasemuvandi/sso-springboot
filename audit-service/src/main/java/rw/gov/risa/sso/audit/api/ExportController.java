package rw.gov.risa.sso.audit.api;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.audit.repository.EventFilter;
import rw.gov.risa.sso.audit.service.EventService;
import rw.gov.risa.sso.audit.service.ExportService;

/**
 * CSV/PDF export (reference routes/export.py). Same filters as the reference, plus
 * {@code institution}, {@code target}, {@code realm} and {@code severity} (additive) so the
 * institution-scoped view can export only its own rows.
 */
@RestController
@RequestMapping("/audit/export")
@Tag(name = "Export")
@Validated
public class ExportController {

    private final EventService events;
    private final ExportService export;

    public ExportController(EventService events, ExportService export) {
        this.events = events;
        this.export = export;
    }

    @GetMapping(value = "/csv", produces = "text/csv")
    @Operation(summary = "Export events as CSV (newest first)")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv",
            schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> csv(
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "user_id", required = false) String userId,
            @RequestParam(name = "app_id", required = false) String appId,
            @RequestParam(name = "realm", required = false) String realm,
            @RequestParam(name = "institution", required = false) String institution,
            @RequestParam(name = "target", required = false) String target,
            @RequestParam(name = "severity", required = false) String severity,
            @Parameter(schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(name = "from_date", required = false) String fromDate,
            @Parameter(schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(name = "to_date", required = false) String toDate,
            @RequestParam(name = "limit", defaultValue = "10000") @Min(1) @Max(50_000) int limit) {
        EventFilter f = new EventFilter(eventType, category, userId, appId, realm, institution, target, severity,
                DateParams.parse("from_date", fromDate), DateParams.parse("to_date", toDate));
        byte[] body = export.toCsv(events.find(f, limit));
        return file(body, "text/csv; charset=utf-8", "audit_export_" + export.stamp() + ".csv");
    }

    @GetMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Export events as a PDF table (newest first)")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/pdf",
            schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> pdf(
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "user_id", required = false) String userId,
            @RequestParam(name = "app_id", required = false) String appId,
            @RequestParam(name = "realm", required = false) String realm,
            @RequestParam(name = "institution", required = false) String institution,
            @RequestParam(name = "target", required = false) String target,
            @RequestParam(name = "severity", required = false) String severity,
            @Parameter(schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(name = "from_date", required = false) String fromDate,
            @Parameter(schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(name = "to_date", required = false) String toDate,
            @RequestParam(name = "limit", defaultValue = "5000") @Min(1) @Max(20_000) int limit) {
        EventFilter f = new EventFilter(eventType, category, userId, appId, realm, institution, target, severity,
                DateParams.parse("from_date", fromDate), DateParams.parse("to_date", toDate));
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("event_type", eventType);
        filters.put("category", category);
        filters.put("user_id", userId);
        filters.put("app_id", appId);
        filters.put("institution", institution);
        filters.put("from", fromDate);
        filters.put("to", toDate);
        byte[] body = export.toPdf(events.find(f, limit), "GoR SSO — Audit Log Export", filters);
        return file(body, MediaType.APPLICATION_PDF_VALUE, "audit_export_" + export.stamp() + ".pdf");
    }

    static ResponseEntity<byte[]> file(byte[] body, String contentType, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }
}
