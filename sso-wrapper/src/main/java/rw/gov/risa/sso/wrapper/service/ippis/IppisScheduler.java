package rw.gov.risa.sso.wrapper.service.ippis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * In-process schedule (IPPIS_SYNC_SCHEDULE_ENABLED=true): a frequent delta and a nightly full pass.
 * Both take the ShedLock lease {@value #LOCK}, so across many instances exactly one runs at a time,
 * a delta never overlaps a full, and a killed run cannot block later ones (the lease expires).
 */
@Component
@ConditionalOnProperty(prefix = "sso.ippis.schedule", name = "enabled", havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("!${sso.ops-mode:false}")
public class IppisScheduler {

    public static final String LOCK = "ippis-reconcile";
    private static final Logger log = LoggerFactory.getLogger(IppisScheduler.class);

    private final IppisReconcileService reconcile;

    public IppisScheduler(IppisReconcileService reconcile) {
        this.reconcile = reconcile;
    }

    @Scheduled(cron = "${sso.ippis.schedule.delta-cron:0 */5 * * * *}")
    @SchedulerLock(name = LOCK, lockAtMostFor = "PT2H", lockAtLeastFor = "PT10S")
    public void delta() {
        try {
            reconcile.runDelta(IppisReconcileService.RunOptions.defaults());
        } catch (RuntimeException e) {
            log.error("ippis reconcile (delta, scheduled) failed: {}", e.toString());
        }
    }

    @Scheduled(cron = "${sso.ippis.schedule.full-cron:0 30 2 * * *}")
    @SchedulerLock(name = LOCK, lockAtMostFor = "PT6H", lockAtLeastFor = "PT10S")
    public void full() {
        try {
            reconcile.runFull(IppisReconcileService.RunOptions.defaults());
        } catch (RuntimeException e) {
            log.error("ippis reconcile (full, scheduled) failed: {}", e.toString());
        }
    }
}
