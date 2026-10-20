package rw.gov.risa.sso.notification.detector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * anomalous_access: WARNING when a user logs in to an app they have never used before.
 *
 * <p>{@code historicalTotals} maps {@code "<user_id>:<app_id>"} to the number of successful
 * logins for that pair in the audit trail up to and including the new login (the caller
 * asks the audit service with {@code limit = threshold + 1}). A total {@code <= threshold}
 * (default 1) means this is the first access. A pair missing from the map counts as 0,
 * as in the reference; the caller puts {@code threshold + 1} there when the lookup fails,
 * so an audit-service outage never produces false positives.
 */
public final class AnomalousAccessRule {

    private AnomalousAccessRule() {
    }

    public static String pairKey(String userId, String appId) {
        return userId + ":" + appId;
    }

    /** The (user, app) pairs that need a history lookup, each with its earliest new login. */
    public static Map<String, AuditEvent> candidates(List<AuditEvent> newEvents) {
        Map<String, AuditEvent> out = new LinkedHashMap<>();
        newEvents.stream()
                .filter(AuditEvent::isLoginSuccess)
                .filter(e -> e.userId() != null && e.appId() != null)
                .sorted(Windows.CHRONOLOGICAL)
                .forEach(e -> out.putIfAbsent(pairKey(e.userId(), e.appId()), e));
        return out;
    }

    public static List<DetectionResult> evaluate(List<AuditEvent> newEvents, Map<String, Integer> historicalTotals,
                                                 int threshold) {
        List<DetectionResult> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AuditEvent e : candidates(newEvents).values()) {
            String key = pairKey(e.userId(), e.appId());
            if (historicalTotals.getOrDefault(key, 0) <= threshold && seen.add(key)) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("first_access_to_app", e.appId());
                out.add(new DetectionResult(RuleNames.ANOMALOUS_ACCESS, Severity.WARNING, e.userId(),
                        e.ipAddress(), e.appId(), details, SubjectKeys.userApp(e.userId(), e.appId())));
            }
        }
        return out;
    }
}
