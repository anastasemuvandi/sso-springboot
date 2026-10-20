package rw.gov.risa.sso.notification.service;

import java.util.UUID;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import rw.gov.risa.sso.notification.web.CorrelationIdFilter;

/**
 * The two scheduled jobs. ShedLock (JDBC, DB time) makes each run once across replicas;
 * {@code lockAtMostFor} is a lease, so a killed run cannot block later ones.
 */
@Configuration
@ConditionalOnProperty(name = "notification.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class Schedulers {

    private static final Logger log = LoggerFactory.getLogger(Schedulers.class);

    @Bean
    @ConditionalOnProperty(name = "notification.detector.enabled", havingValue = "true", matchIfMissing = true)
    DetectorJob detectorJob(DetectionService detection) {
        return new DetectorJob(detection);
    }

    @Bean
    @ConditionalOnProperty(name = "notification.report.enabled", havingValue = "true", matchIfMissing = true)
    DailyReportJob dailyReportJob(DailyReportService reports) {
        return new DailyReportJob(reports);
    }

    public static class DetectorJob {
        private final DetectionService detection;

        DetectorJob(DetectionService detection) {
            this.detection = detection;
        }

        /** Reference beat schedule: every 60 s. */
        @Scheduled(fixedDelayString = "${notification.detector.interval-ms:60000}",
                initialDelayString = "${notification.detector.initial-delay-ms:15000}")
        @SchedulerLock(name = "notification-detector", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
        public void run() {
            MDC.put(CorrelationIdFilter.MDC_KEY, "detector-" + UUID.randomUUID());
            try {
                detection.runOnce();
            } catch (RuntimeException e) {
                log.error("Detection cycle failed; watermark not advanced: {}", e.toString());
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }

    public static class DailyReportJob {
        private final DailyReportService reports;

        DailyReportJob(DailyReportService reports) {
            this.reports = reports;
        }

        /** Reference: crontab(hour=8, minute=0), Africa/Kigali. */
        @Scheduled(cron = "${notification.report.cron:0 0 8 * * *}", zone = "${notification.report.zone:Africa/Kigali}")
        @SchedulerLock(name = "notification-daily-report", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
        public void run() {
            MDC.put(CorrelationIdFilter.MDC_KEY, "daily-report-" + UUID.randomUUID());
            try {
                reports.send();
            } catch (RuntimeException e) {
                log.error("Daily report failed: {}", e.toString());
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }
}
