package rw.gov.risa.sso.audit.api;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.audit.api.dto.Reports;
import rw.gov.risa.sso.audit.api.dto.ServiceHealthRead;
import rw.gov.risa.sso.audit.repository.EventFilter;
import rw.gov.risa.sso.audit.service.EventService;
import rw.gov.risa.sso.audit.service.ExportService;
import rw.gov.risa.sso.audit.service.ReportService;

/** Reference routes/health.py: health history of monitored services, and reports. */
@RestController
@RequestMapping("/audit")
@Tag(name = "Health & Reports")
@Validated
public class HealthReportsController {

    private final ReportService reports;
    private final EventService events;
    private final ExportService export;
    private final Clock clock;

    public HealthReportsController(ReportService reports, EventService events, ExportService export, Clock clock) {
        this.reports = reports;
        this.events = events;
        this.export = export;
        this.clock = clock;
    }

    @GetMapping("/health/services")
    @Operation(summary = "Latest health snapshots of every monitored service")
    public List<ServiceHealthRead> services(@RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(500) int limit) {
        return reports.latestHealth(limit);
    }

    @GetMapping("/health/services/{service_name}")
    @Operation(summary = "Health history of one service")
    public List<ServiceHealthRead> service(@PathVariable("service_name") String serviceName,
                                           @RequestParam(name = "limit", defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return reports.healthHistory(serviceName, limit);
    }

    @GetMapping("/reports/cert-expiry")
    @Operation(summary = "Certificates expiring within days_ahead days")
    public Reports.CertExpiryReport certExpiry(
            @RequestParam(name = "days_ahead", defaultValue = "90") @Min(1) @Max(365) int daysAhead) {
        return reports.certExpiry(daysAhead);
    }

    @GetMapping("/reports/access-analytics")
    @Operation(summary = "Top apps, failed-login trend, access denials")
    public Reports.AccessAnalytics accessAnalytics(
            @Parameter(schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(name = "from_date", required = false) String fromDate,
            @Parameter(schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(name = "to_date", required = false) String toDate) {
        return reports.accessAnalytics(DateParams.parse("from_date", fromDate), DateParams.parse("to_date", toDate));
    }

    @GetMapping(value = "/reports/compliance", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Compliance report PDF (default: the last 30 days)")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/pdf",
            schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> compliance(
            @Parameter(schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(name = "from_date", required = false) String fromDate,
            @Parameter(schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(name = "to_date", required = false) String toDate) {
        OffsetDateTime from = DateParams.parse("from_date", fromDate);
        OffsetDateTime to = DateParams.parse("to_date", toDate);
        if (from == null) {
            from = OffsetDateTime.now(clock).minusDays(30);
        }
        if (to == null) {
            to = OffsetDateTime.now(clock);
        }
        byte[] pdf = export.compliancePdf(events.find(EventFilter.window(from, to), 50_000), from, to);
        return ExportController.file(pdf, MediaType.APPLICATION_PDF_VALUE,
                "compliance_" + from.toLocalDate() + "_" + to.toLocalDate() + ".pdf");
    }
}
