package rw.gov.risa.sso.notification.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import rw.gov.risa.sso.notification.api.AlertRead;
import rw.gov.risa.sso.notification.api.DailyReport;
import rw.gov.risa.sso.notification.api.DailyReportSendResult;
import rw.gov.risa.sso.notification.channel.Delivery;
import rw.gov.risa.sso.notification.channel.EmailChannel;
import rw.gov.risa.sso.notification.config.NotificationProperties;
import rw.gov.risa.sso.notification.detector.AlertText;
import rw.gov.risa.sso.notification.repository.AlertRepository;
import rw.gov.risa.sso.notification.repository.AlertRow;

/**
 * Reference {@code send_daily_report}: the last 24 h of alerts summarised and emailed to
 * ADMIN_EMAILS at 08:00 Africa/Kigali. Skipped (and said so) when there are no admin emails,
 * no alerts, or no SMTP.
 */
@Service
public class DailyReportService {

    private static final Logger log = LoggerFactory.getLogger(DailyReportService.class);
    static final int MAX_ROWS = 50;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final AlertRepository alerts;
    private final EmailChannel email;
    private final NotificationProperties props;
    private final Clock clock;

    public DailyReportService(AlertRepository alerts, EmailChannel email, NotificationProperties props, Clock clock) {
        this.alerts = alerts;
        this.email = email;
        this.props = props;
        this.clock = clock;
    }

    public DailyReport build() {
        Instant end = clock.instant();
        Instant since = end.minus(Duration.ofHours(24));
        return summarize(alerts.createdSince(since), since, end);
    }

    static DailyReport summarize(List<AlertRow> rows, Instant since, Instant end) {
        int critical = 0;
        int warning = 0;
        int ack = 0;
        Map<String, Integer> byRule = new LinkedHashMap<>();
        for (AlertRow a : rows) {
            if ("CRITICAL".equals(a.severity())) {
                critical++;
            } else if ("WARNING".equals(a.severity())) {
                warning++;
            }
            if (a.acknowledged()) {
                ack++;
            }
            byRule.merge(a.ruleName(), 1, Integer::sum);
        }
        Map<String, Integer> sorted = new LinkedHashMap<>();
        byRule.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        List<AlertRead> latest = rows.stream().limit(MAX_ROWS).map(AlertRead::of).toList();
        return new DailyReport(since, end, rows.size(), critical, warning, ack, sorted, latest);
    }

    public DailyReportSendResult send() {
        List<String> admins = props.adminEmailList();
        DailyReport report = build();
        if (admins.isEmpty()) {
            log.info("No admin emails configured — skipping daily report");
            return new DailyReportSendResult(Delivery.SKIPPED, report.total(), "", "no admin emails configured");
        }
        if (report.total() == 0) {
            log.info("Daily report: no alerts in last 24 hours — skipping email");
            return new DailyReportSendResult(Delivery.SKIPPED, 0, String.join(", ", admins),
                    "no alerts in the last 24 hours");
        }
        Context ctx = new Context();
        ctx.setVariable("report", report);
        ctx.setVariable("periodStartDate", DAY.format(report.periodStart()));
        ctx.setVariable("periodEndDate", DAY.format(report.periodEnd()));
        ctx.setVariable("byRule", report.byRule().entrySet().stream()
                .map(e -> Map.of("title", AlertText.humanize(e.getKey()), "count", e.getValue())).toList());
        ctx.setVariable("rows", report.latest().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", a.createdAt() == null ? "" : HHMM.format(a.createdAt()));
            m.put("severity", a.severity());
            m.put("color", severityColor(a.severity()));
            m.put("rule", AlertText.humanize(a.ruleName()));
            m.put("user", a.userId() == null ? "—" : a.userId());
            m.put("acked", a.acknowledged());
            return m;
        }).toList());
        String subject = "[Daily Report] SSO Security Alerts — " + DAY.format(report.periodEnd());
        Delivery d = email.send(subject, "email/daily-report", ctx, admins);
        log.info("Daily report {} ({} alerts)", d.status(), report.total());
        return new DailyReportSendResult(d.status(), report.total(), d.recipient(), d.error());
    }

    private static String severityColor(String s) {
        return switch (s == null ? "" : s) {
            case "CRITICAL" -> "#cc0000";
            case "WARNING" -> "#e07000";
            case "INFO" -> "#0070cc";
            default -> "#333";
        };
    }
}
