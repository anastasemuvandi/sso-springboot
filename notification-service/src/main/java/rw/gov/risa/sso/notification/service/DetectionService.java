package rw.gov.risa.sso.notification.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import rw.gov.risa.sso.notification.audit.AuditClient;
import rw.gov.risa.sso.notification.config.NotificationProperties;
import rw.gov.risa.sso.notification.detector.AnomalousAccessRule;
import rw.gov.risa.sso.notification.detector.AuditEvent;
import rw.gov.risa.sso.notification.detector.DetectionResult;
import rw.gov.risa.sso.notification.detector.Detector;
import rw.gov.risa.sso.notification.detector.RuleConfig;
import rw.gov.risa.sso.notification.detector.RuleNames;
import rw.gov.risa.sso.notification.repository.DetectorStateRepository;

/**
 * One detector cycle (reference {@code run_detection}), with a watermark so audit events are
 * processed once:
 * <ol>
 *   <li>{@code W} = last cycle's upper bound (first cycle: now − initial look-back, 60 min);
 *       this cycle reads up to {@code T = now − settle} (a few seconds, for in-flight inserts).</li>
 *   <li>Fetch {@code [W − context, T]}; context = the longest rule window (30 min) so a burst
 *       that straddles cycles is still counted.</li>
 *   <li>"New" = events with {@code created_at >= W − overlap} whose id is not in
 *       {@code processed_audit_events}. Only new events can trigger a rule.</li>
 *   <li>Detect, persist + dispatch (cooldowns apply), mark the new ids processed, move
 *       {@code W} to {@code T}.</li>
 * </ol>
 * If the audit service cannot be read the cycle aborts without moving the watermark, so the
 * next cycle re-reads the same window (the reference logged and carried on with no events).
 */
@Service
public class DetectionService {

    public static final String STATE_NAME = "detector";
    private static final Logger log = LoggerFactory.getLogger(DetectionService.class);

    public record RunResult(int fetched, int newEvents, int detections, int alertsCreated, Instant watermark) {
    }

    private final AuditClient audit;
    private final DetectorStateRepository state;
    private final ConfigService configs;
    private final Alerter alerter;
    private final NotificationProperties props;
    private final Clock clock;

    public DetectionService(AuditClient audit, DetectorStateRepository state, ConfigService configs, Alerter alerter,
                            NotificationProperties props, Clock clock) {
        this.audit = audit;
        this.state = state;
        this.configs = configs;
        this.alerter = alerter;
        this.props = props;
        this.clock = clock;
    }

    public RunResult runOnce() {
        NotificationProperties.Detector d = props.detector();
        Instant now = clock.instant();
        Instant watermark = state.watermark(STATE_NAME)
                .orElse(now.minus(Duration.ofMinutes(d.initialLookbackMinutes())));
        Instant upTo = now.minusSeconds(d.settleSeconds());
        if (!upTo.isAfter(watermark)) {
            return new RunResult(0, 0, 0, 0, watermark);
        }
        Map<String, RuleConfig> cfg = configs.effective();
        int contextMinutes = Math.max(d.overlapMinutes(), Math.max(
                cfg.get(RuleNames.BRUTE_FORCE).windowOr(10), cfg.get(RuleNames.IMPOSSIBLE_TRAVEL).windowOr(30)));
        Instant from = watermark.minus(Duration.ofMinutes(contextMinutes));

        List<AuditEvent> events = audit.fetchEvents(from, upTo);

        Instant newFrom = watermark.minus(Duration.ofMinutes(d.overlapMinutes()));
        List<AuditEvent> candidates = events.stream()
                .filter(e -> !e.createdAt().isBefore(newFrom) && !e.createdAt().isAfter(upTo))
                .toList();
        Set<String> seen = state.alreadyProcessed(candidates.stream().map(AuditEvent::id).toList());
        List<AuditEvent> fresh = candidates.stream().filter(e -> !seen.contains(e.id())).toList();
        Set<String> freshIds = fresh.stream().map(AuditEvent::id).collect(Collectors.toSet());

        int alertsCreated = 0;
        int detections = 0;
        if (!fresh.isEmpty()) {
            Map<String, Integer> history = cfg.get(RuleNames.ANOMALOUS_ACCESS).enabled()
                    ? history(fresh, cfg.get(RuleNames.ANOMALOUS_ACCESS).thresholdOr(1))
                    : Map.of();
            List<DetectionResult> results = Detector.detect(new Detector.Input(events, freshIds, cfg, history,
                    props.timezoneOffsetHours(), props.businessStartHour(), props.businessEndHour()));
            detections = results.size();
            alertsCreated = alerter.process(results, cfg);
            state.markProcessed(fresh, now);
        }
        state.save(STATE_NAME, upTo, now, fresh.size(), alertsCreated);
        state.pruneProcessedBefore(upTo.minus(Duration.ofDays(1)));
        if (alertsCreated > 0 || !fresh.isEmpty()) {
            log.info("Detection cycle: fetched={} new={} detections={} alerts={}", events.size(), fresh.size(),
                    detections, alertsCreated);
        }
        return new RunResult(events.size(), fresh.size(), detections, alertsCreated, upTo);
    }

    /**
     * Reference {@code _fetch_historical_totals}: per (user, app) pair, how many successful
     * logins the audit trail holds up to the pair's earliest new login, capped at
     * threshold + 1. A failed lookup counts as "not first" (no false positives).
     */
    private Map<String, Integer> history(List<AuditEvent> fresh, int threshold) {
        Map<String, Integer> totals = new HashMap<>();
        AnomalousAccessRule.candidates(fresh).forEach((key, e) -> {
            try {
                totals.put(key, audit.countLogins(e.userId(), e.appId(), e.createdAt(), threshold + 1));
            } catch (RuntimeException ex) {
                log.warn("History lookup failed for an (user, app) pair; assuming not first access: {}",
                        ex.getClass().getSimpleName());
                totals.put(key, threshold + 1);
            }
        });
        return totals;
    }
}
