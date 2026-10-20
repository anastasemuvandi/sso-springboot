package rw.gov.risa.sso.audit.workers;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import rw.gov.risa.sso.audit.api.dto.AuditEventCreate;
import rw.gov.risa.sso.audit.api.dto.Severity;
import rw.gov.risa.sso.audit.chain.HashChain;
import rw.gov.risa.sso.audit.repository.AuditEventRepository;
import rw.gov.risa.sso.audit.service.AuditWriter;

/**
 * Quarterly delegated-access review (reference workers/access_review.py): 07:00 Africa/Kigali on
 * 1 Jan/Apr/Jul/Oct, one {@code admin.access_review.due} (WARNING) per institution seen in the
 * trail, carrying a summary of the last 92 days of reviewable actions. Writing it to the trail
 * makes "was the review raised?" itself tamper-evident.
 */
@Component
@ConditionalOnProperty(name = "audit.workers.enabled", havingValue = "true", matchIfMissing = true)
public class AccessReviewWorker {

    private static final Logger log = LoggerFactory.getLogger(AccessReviewWorker.class);

    public static final String EVENT = "admin.access_review.due";
    public static final String ACTOR = "system:access-review";
    public static final int REVIEW_WINDOW_DAYS = 92;
    /** Actions that change who can do what. A login is not an access-review concern. */
    public static final List<String> REVIEWABLE_EVENT_TYPES = List.of(
            "admin.role.assigned", "admin.role.removed", "admin.user.created", "admin.user.disabled",
            "admin.client.updated");
    static final String ACTION_REQUIRED = "Re-confirm every holder of this institution's inst:<slug>:* roles "
            + "is still entitled to it. Revoke the rest.";

    private final AuditEventRepository events;
    private final AuditWriter writer;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AccessReviewWorker(AuditEventRepository events, AuditWriter writer, TransactionTemplate tx, Clock clock) {
        this.events = events;
        this.writer = writer;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(cron = "${audit.workers.access-review-cron:0 0 7 1 1,4,7,10 *}", zone = "Africa/Kigali")
    @SchedulerLock(name = "audit.access_review", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void scheduled() {
        Map<String, Object> r = run();
        log.info("Access reviews raised for {} institutions", r.get("institutions"));
    }

    public Map<String, Object> run() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime since = now.minusDays(REVIEW_WINDOW_DAYS);
        Map<String, Object> raised = new LinkedHashMap<>();
        tx.executeWithoutResult(status -> {
            for (String institution : events.institutions()) {
                Map<String, Object> summary = summary(institution, since);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("window_days", REVIEW_WINDOW_DAYS);
                details.put("window_from", HashChain.pyIsoFormat(HashChain.toMicros(since)));
                details.put("window_to", HashChain.pyIsoFormat(HashChain.toMicros(now)));
                details.put("summary", summary);
                details.put("action_required", ACTION_REQUIRED);
                // The institution is what is under review, so it is also the target.
                writer.append(AuditEventCreate.system(EVENT, null, institution, institution, ACTOR, details,
                        Severity.WARNING));
                raised.put(institution, summary);
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("institutions", raised.size());
        out.put("raised", raised);
        out.put("at", HashChain.pyIsoFormat(HashChain.toMicros(now)));
        return out;
    }

    Map<String, Object> summary(String institution, OffsetDateTime since) {
        Map<String, Long> byType = events.countByType(institution, since, REVIEWABLE_EVENT_TYPES);
        Map<String, Long> byActor = events.countByActor(institution, since, REVIEWABLE_EVENT_TYPES);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("events_by_type", byType);
        s.put("events_by_actor", byActor);
        s.put("reviewable_total", byType.values().stream().mapToLong(Long::longValue).sum());
        s.put("distinct_actors", byActor.size());
        return s;
    }
}
