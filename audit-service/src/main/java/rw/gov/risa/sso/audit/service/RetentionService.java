package rw.gov.risa.sso.audit.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import rw.gov.risa.sso.audit.api.dto.AuditEventCreate;
import rw.gov.risa.sso.audit.api.dto.RetentionPolicyCreate;
import rw.gov.risa.sso.audit.api.dto.RetentionPolicyRead;
import rw.gov.risa.sso.audit.api.dto.RetentionRunResult;
import rw.gov.risa.sso.audit.api.dto.Severity;
import rw.gov.risa.sso.audit.config.AuditProperties;
import rw.gov.risa.sso.audit.repository.AuditEventRepository;
import rw.gov.risa.sso.audit.repository.RetentionPolicyRepository;
import rw.gov.risa.sso.audit.repository.ServiceHealthRepository;

/**
 * Retention policies and legal hold (reference core/retention.py).
 *
 * <p>Rules, as the reference:
 * <ul>
 *   <li>the policy with category NULL is the default; without one, the default is 365 days;</li>
 *   <li>a default policy on legal hold means "keep for 999 999 days" for every category that has
 *       no policy of its own;</li>
 *   <li>a category policy on legal hold is skipped entirely;</li>
 *   <li>otherwise rows of that category older than {@code now - retention_days} are deleted.</li>
 * </ul>
 * Additions: the purge is the only path past the append-only trigger (transaction-local flag),
 * each purged row leaves a hash link so the chain still verifies, and both policy changes and
 * non-empty purges are themselves written to the trail (category ADMIN).
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    static final int DEFAULT_DAYS = 365;
    static final int HOLD_DAYS = 999_999;

    public static final String EVENT_POLICY_SET = "admin.audit_retention.policy_set";
    public static final String EVENT_APPLIED = "admin.audit_retention.applied";

    private final RetentionPolicyRepository policies;
    private final AuditEventRepository events;
    private final ServiceHealthRepository health;
    private final AuditWriter writer;
    private final AuditProperties props;
    private final Clock clock;

    public RetentionService(RetentionPolicyRepository policies, AuditEventRepository events,
                            ServiceHealthRepository health, AuditWriter writer, AuditProperties props, Clock clock) {
        this.policies = policies;
        this.events = events;
        this.health = health;
        this.writer = writer;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RetentionPolicyRead> list() {
        return policies.findAll();
    }

    @Transactional
    public RetentionPolicyRead set(RetentionPolicyCreate in, String actor) {
        Optional<RetentionPolicyRead> before = policies.findForUpdate(in.category());
        RetentionPolicyRead saved = policies.upsert(in.category(), in.retentionDays(), in.legalHold());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("category", in.category());
        details.put("retention_days", saved.retentionDays());
        details.put("legal_hold", saved.legalHold());
        before.ifPresent(b -> {
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("retention_days", b.retentionDays());
            prev.put("legal_hold", b.legalHold());
            details.put("previous", prev);
        });
        writer.append(AuditEventCreate.system(EVENT_POLICY_SET, null, null,
                in.category() == null ? "retention:default" : "retention:" + in.category(), actor, details,
                Severity.WARNING));
        return saved;
    }

    /** One transaction: the purge flag, every category's delete, and the summary event. */
    @Transactional
    public RetentionRunResult apply(String actor) {
        List<RetentionPolicyRead> all = policies.findAll();
        int defaultDays = DEFAULT_DAYS;
        Map<String, RetentionPolicyRead> byCategory = new LinkedHashMap<>();
        for (RetentionPolicyRead p : all) {
            if (p.category() == null) {
                defaultDays = p.legalHold() ? HOLD_DAYS : p.retentionDays();
            } else {
                byCategory.put(p.category(), p);
            }
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        events.enableRetentionPurge();
        long total = 0;
        Map<String, Object> perCategory = new LinkedHashMap<>();
        List<String> held = new java.util.ArrayList<>();
        for (String category : events.distinctCategories()) {
            RetentionPolicyRead p = byCategory.get(category);
            int days = p == null ? defaultDays : p.retentionDays();
            if (p != null && p.legalHold()) {
                held.add(category);
                continue;
            }
            int n = events.purgeOlderThan(category, now.minusDays(days));
            if (n > 0) {
                perCategory.put(category, n);
            }
            total += n;
        }

        int pruned = health.pruneOlderThan(now.minusDays(Math.max(1, props.retention().serviceHealthDays())));
        if (total > 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("deleted", total);
            details.put("deleted_by_category", perCategory);
            details.put("legal_hold_categories", held);
            details.put("default_retention_days", defaultDays);
            writer.append(AuditEventCreate.system(EVENT_APPLIED, null, null, "audit_logs", actor, details,
                    Severity.WARNING));
        }
        log.info("Retention applied: {} audit rows removed, {} health snapshots pruned, held={}", total, pruned, held);
        return new RetentionRunResult(total, "Removed " + total + " expired audit log rows.");
    }
}
