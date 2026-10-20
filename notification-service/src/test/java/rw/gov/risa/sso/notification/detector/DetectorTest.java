package rw.gov.risa.sso.notification.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static rw.gov.risa.sso.notification.detector.Ev.fail;
import static rw.gov.risa.sso.notification.detector.Ev.ok;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class DetectorTest {

    private static final Instant NIGHT = Instant.parse("2026-10-20T21:30:00Z"); // 23:30 Kigali

    private static List<AuditEvent> scenario() {
        List<AuditEvent> ev = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ev.add(fail("alice", "10.0.0.1", NIGHT.plusSeconds(i * 30L)));
        }
        ev.add(ok("bob", "1.1.1.1", "minagri-web", NIGHT));
        ev.add(ok("bob", "2.2.2.2", "minagri-web", NIGHT.plusSeconds(600)));
        return ev;
    }

    private static Set<String> ids(List<AuditEvent> ev) {
        return ev.stream().map(AuditEvent::id).collect(Collectors.toSet());
    }

    @Test
    void allFourRulesFireOnOneCycle() {
        List<AuditEvent> ev = scenario();
        List<DetectionResult> r = Detector.detect(new Detector.Input(ev, ids(ev), RuleConfigs.defaults(),
                Map.of("bob:minagri-web", 1), 2, 6, 22));
        assertThat(r).extracting(DetectionResult::ruleName).containsExactly(
                "brute_force", "unusual_hours", "impossible_travel", "anomalous_access");
    }

    @Test
    void disabledRulesAreNotEvaluated() {
        List<AuditEvent> ev = scenario();
        Map<String, RuleConfig> cfg = new LinkedHashMap<>(RuleConfigs.defaults());
        cfg.put("brute_force", RuleConfigs.merge(cfg.get("brute_force"),
                new RuleConfigOverride("brute_force", false, null, null, null, null, null)));
        cfg.put("unusual_hours", RuleConfigs.merge(cfg.get("unusual_hours"),
                new RuleConfigOverride("unusual_hours", false, null, null, null, null, null)));
        List<DetectionResult> r = Detector.detect(new Detector.Input(ev, ids(ev), cfg,
                Map.of("bob:minagri-web", 5), 2, 6, 22));
        assertThat(r).extracting(DetectionResult::ruleName).containsExactly("impossible_travel");
    }

    @Test
    void nothingNewNothingFires() {
        List<AuditEvent> ev = scenario();
        assertThat(Detector.detect(new Detector.Input(ev, Set.of(), RuleConfigs.defaults(), Map.of(), 2, 6, 22)))
                .isEmpty();
    }

    @Test
    void configuredThresholdIsHonoured() {
        List<AuditEvent> ev = scenario();
        Map<String, RuleConfig> cfg = new LinkedHashMap<>(RuleConfigs.defaults());
        cfg.put("brute_force", RuleConfigs.merge(cfg.get("brute_force"),
                new RuleConfigOverride("brute_force", true, 6, null, null, null, null)));
        List<DetectionResult> r = Detector.detect(new Detector.Input(ev, ids(ev), cfg,
                Map.of("bob:minagri-web", 5), 2, 6, 22));
        assertThat(r).extracting(DetectionResult::ruleName).doesNotContain("brute_force");
    }
}
