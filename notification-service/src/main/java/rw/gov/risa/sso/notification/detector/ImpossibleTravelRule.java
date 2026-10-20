package rw.gov.risa.sso.notification.detector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * impossible_travel: CRITICAL when the same user logs in successfully from
 * {@code >= threshold} (default 2) distinct IP addresses within {@code windowMinutes}
 * (default 30) — a stolen credential or token replayed from elsewhere while the victim
 * is active. The window is anchored on each new successful login.
 */
public final class ImpossibleTravelRule {

    private ImpossibleTravelRule() {
    }

    public static List<DetectionResult> evaluate(List<AuditEvent> events, Predicate<AuditEvent> isNew,
                                                 int threshold, int windowMinutes) {
        List<AuditEvent> logins = events.stream()
                .filter(AuditEvent::isLoginSuccess)
                .filter(e -> e.userId() != null && e.ipAddress() != null)
                .sorted(Windows.CHRONOLOGICAL)
                .toList();
        Map<String, DetectionResult> byUser = new LinkedHashMap<>();
        for (AuditEvent anchor : logins) {
            if (!isNew.test(anchor)) {
                continue;
            }
            List<AuditEvent> window = Windows.within(logins, anchor.createdAt(), windowMinutes,
                    e -> anchor.userId().equals(e.userId()));
            List<String> ips = window.stream().map(AuditEvent::ipAddress).distinct().toList();
            if (ips.size() >= threshold) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("distinct_ips", ips);
                details.put("login_count", window.size());
                byUser.put(anchor.userId(), new DetectionResult(RuleNames.IMPOSSIBLE_TRAVEL, Severity.CRITICAL,
                        anchor.userId(), anchor.ipAddress(), null, details, SubjectKeys.user(anchor.userId())));
            }
        }
        return new ArrayList<>(byUser.values());
    }
}
