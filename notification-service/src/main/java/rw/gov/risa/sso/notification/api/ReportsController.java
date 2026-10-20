package rw.gov.risa.sso.notification.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.gov.risa.sso.notification.config.NotificationConfig;
import rw.gov.risa.sso.notification.service.DailyReportService;

/**
 * Not in the reference's HTTP surface (it only had the 08:00 Celery beat job): the same report
 * as JSON, and a manual trigger for the email.
 */
@RestController
@RequestMapping("/reports")
@Tag(name = "reports")
@SecurityRequirement(name = NotificationConfig.API_KEY_SCHEME)
public class ReportsController {

    private final DailyReportService reports;

    public ReportsController(DailyReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/daily")
    @Operation(summary = "Summary of the alerts of the last 24 hours")
    public DailyReport daily() {
        return reports.build();
    }

    @PostMapping("/daily/send")
    @Operation(summary = "Email the daily report to ADMIN_EMAILS now (also runs at 08:00 Africa/Kigali)")
    public DailyReportSendResult send() {
        return reports.send();
    }
}
