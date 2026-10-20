package rw.gov.risa.sso.audit.workers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import rw.gov.risa.sso.audit.service.RetentionService;

/** Daily 02:00 Africa/Kigali retention purge (reference apply_retention_policy). */
@Component
@ConditionalOnProperty(name = "audit.workers.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionWorker {

    private static final Logger log = LoggerFactory.getLogger(RetentionWorker.class);
    public static final String ACTOR = "system:retention";

    private final RetentionService retention;

    public RetentionWorker(RetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "${audit.workers.retention-cron:0 0 2 * * *}", zone = "Africa/Kigali")
    @SchedulerLock(name = "audit.retention", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void scheduled() {
        var r = retention.apply(ACTOR);
        log.info("Retention run removed {} rows", r.deleted());
    }
}
