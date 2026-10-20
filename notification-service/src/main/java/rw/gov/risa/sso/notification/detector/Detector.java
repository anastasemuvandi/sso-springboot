package rw.gov.risa.sso.notification.detector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Runs every enabled rule over one detector cycle's events. Pure: the caller fetches the
 * events and the anomalous-access history and persists/dispatches the results.
 *
 * <p>{@code events} is everything fetched (new events plus look-back context);
 * {@code newEventIds} are the events no earlier run has processed. Only new events can
 * trigger a rule, which is what stops a cycle re-alerting on events a previous cycle saw.
 */
public final class Detector {

    public record Input(
            List<AuditEvent> events,
            Set<String> newEventIds,
            Map<String, RuleConfig> configs,
            Map<String, Integer> historicalTotals,
            int tzOffsetHours,
            int businessStartHour,
            int businessEndHour) {
    }

    private Detector() {
    }

    public static List<DetectionResult> detect(Input in) {
        Predicate<AuditEvent> isNew = e -> in.newEventIds().contains(e.id());
        List<AuditEvent> newEvents = in.events().stream().filter(isNew).toList();
        List<DetectionResult> out = new ArrayList<>();

        RuleConfig bf = config(in, RuleNames.BRUTE_FORCE);
        if (bf.enabled()) {
            out.addAll(BruteForceRule.evaluate(in.events(), isNew, bf.thresholdOr(5), bf.windowOr(10)));
        }
        RuleConfig uh = config(in, RuleNames.UNUSUAL_HOURS);
        if (uh.enabled()) {
            out.addAll(UnusualHoursRule.evaluate(newEvents, in.tzOffsetHours(),
                    in.businessStartHour(), in.businessEndHour()));
        }
        RuleConfig it = config(in, RuleNames.IMPOSSIBLE_TRAVEL);
        if (it.enabled()) {
            out.addAll(ImpossibleTravelRule.evaluate(in.events(), isNew, it.thresholdOr(2), it.windowOr(30)));
        }
        RuleConfig aa = config(in, RuleNames.ANOMALOUS_ACCESS);
        if (aa.enabled()) {
            out.addAll(AnomalousAccessRule.evaluate(newEvents, in.historicalTotals(), aa.thresholdOr(1)));
        }
        return out;
    }

    private static RuleConfig config(Input in, String rule) {
        RuleConfig c = in.configs().get(rule);
        return c != null ? c : RuleConfigs.defaults().get(rule);
    }
}
