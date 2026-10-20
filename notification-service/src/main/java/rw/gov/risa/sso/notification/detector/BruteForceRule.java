package rw.gov.risa.sso.notification.detector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * brute_force: CRITICAL when one user OR one IP produces {@code >= threshold} failed
 * logins within {@code windowMinutes}. Both clusters are counted so credential stuffing
 * (many usernames, one IP) and password spraying (one username, many IPs) are caught.
 *
 * <p>The window is anchored on each <em>new</em> failure (one not seen by an earlier
 * detector run) and looks back over all fetched events, so a burst that straddles two
 * detector runs is still counted once it reaches the threshold.
 *
 * <p>When an IP cluster consists solely of failures for a single user who already has a
 * user-cluster alert in this run, the IP alert is the same incident and is not repeated.
 */
public final class BruteForceRule {

    static final int MAX_LISTED = 10;

    private BruteForceRule() {
    }

    public static List<DetectionResult> evaluate(List<AuditEvent> events, Predicate<AuditEvent> isNew,
                                                 int threshold, int windowMinutes) {
        List<AuditEvent> failures = events.stream()
                .filter(AuditEvent::isLoginFailure)
                .sorted(Windows.CHRONOLOGICAL)
                .toList();

        Map<String, DetectionResult> byUser = new LinkedHashMap<>();
        Map<String, DetectionResult> byIp = new LinkedHashMap<>();
        Map<String, List<AuditEvent>> ipClusters = new LinkedHashMap<>();

        for (AuditEvent anchor : failures) {
            if (!isNew.test(anchor)) {
                continue;
            }
            if (anchor.userId() != null) {
                List<AuditEvent> cluster = Windows.within(failures, anchor.createdAt(), windowMinutes,
                        e -> anchor.userId().equals(e.userId()));
                if (cluster.size() >= threshold) {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("count", cluster.size());
                    details.put("cluster", "user");
                    details.put("ips", distinct(cluster.stream().map(AuditEvent::ipAddress).toList()));
                    byUser.put(anchor.userId(), new DetectionResult(RuleNames.BRUTE_FORCE, Severity.CRITICAL,
                            anchor.userId(), anchor.ipAddress(), null, details, SubjectKeys.user(anchor.userId())));
                }
            }
            if (anchor.ipAddress() != null) {
                List<AuditEvent> cluster = Windows.within(failures, anchor.createdAt(), windowMinutes,
                        e -> anchor.ipAddress().equals(e.ipAddress()));
                if (cluster.size() >= threshold) {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("count", cluster.size());
                    details.put("cluster", "ip");
                    details.put("usernames", distinct(cluster.stream().map(AuditEvent::userId).toList()));
                    byIp.put(anchor.ipAddress(), new DetectionResult(RuleNames.BRUTE_FORCE, Severity.CRITICAL,
                            anchor.userId(), anchor.ipAddress(), null, details, SubjectKeys.ip(anchor.ipAddress())));
                    ipClusters.put(anchor.ipAddress(), cluster);
                }
            }
        }

        List<DetectionResult> out = new ArrayList<>(byUser.values());
        for (Map.Entry<String, DetectionResult> e : byIp.entrySet()) {
            if (!sameIncidentAsUserAlert(ipClusters.get(e.getKey()), byUser)) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    private static boolean sameIncidentAsUserAlert(List<AuditEvent> ipCluster, Map<String, DetectionResult> byUser) {
        Set<String> users = new LinkedHashSet<>();
        for (AuditEvent e : ipCluster) {
            if (e.userId() == null) {
                return false;
            }
            users.add(e.userId());
        }
        return users.size() == 1 && byUser.containsKey(users.iterator().next());
    }

    static List<String> distinct(List<String> values) {
        return values.stream().filter(Objects::nonNull).distinct().limit(MAX_LISTED).toList();
    }
}
