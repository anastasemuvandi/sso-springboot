package rw.gov.risa.sso.notification.detector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RuleConfigsTest {

    @Test
    void defaultsMatchTheReference() {
        Map<String, RuleConfig> d = RuleConfigs.defaults();
        assertThat(d.keySet()).containsExactly("brute_force", "unusual_hours", "impossible_travel",
                "anomalous_access", "stale_session", "multiple_sessions");
        RuleConfig bf = d.get("brute_force");
        assertThat(bf.threshold()).isEqualTo(5);
        assertThat(bf.windowMinutes()).isEqualTo(10);
        assertThat(bf.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(bf.channels()).containsExactly("email", "inapp", "webhook");
        assertThat(bf.cooldownMinutes()).isEqualTo(5);
        assertThat(d.get("unusual_hours").cooldownMinutes()).isEqualTo(60);
        assertThat(d.get("unusual_hours").channels()).containsExactly("inapp");
        assertThat(d.get("impossible_travel").threshold()).isEqualTo(2);
        assertThat(d.get("impossible_travel").windowMinutes()).isEqualTo(30);
        assertThat(d.get("anomalous_access").cooldownMinutes()).isEqualTo(1440);
        assertThat(d.get("stale_session").enabled()).isFalse();
        assertThat(d.get("multiple_sessions").enabled()).isFalse();
    }

    @Test
    void cooldownEnvKnobsFeedTheDefaults() {
        Map<String, RuleConfig> d = RuleConfigs.defaults(7, 90);
        assertThat(d.get("brute_force").cooldownMinutes()).isEqualTo(7);
        assertThat(d.get("impossible_travel").cooldownMinutes()).isEqualTo(7);
        assertThat(d.get("unusual_hours").cooldownMinutes()).isEqualTo(90);
        assertThat(d.get("anomalous_access").cooldownMinutes()).isEqualTo(1440);
    }

    @Test
    void noRowMeansDefaults() {
        RuleConfig def = RuleConfigs.defaults().get("brute_force");
        assertThat(RuleConfigs.merge(def, null)).isEqualTo(def);
    }

    @Test
    void rowOverridesEveryField() {
        RuleConfig def = RuleConfigs.defaults().get("brute_force");
        RuleConfig m = RuleConfigs.merge(def, new RuleConfigOverride("brute_force", false, 8, 20, "warning",
                List.of("inapp"), 15));
        assertThat(m).isEqualTo(new RuleConfig("brute_force", false, 8, 20, Severity.WARNING, List.of("inapp"), 15));
    }

    @Test
    void nullZeroOrEmptyFallBackToDefaultLikePythonOr() {
        RuleConfig def = RuleConfigs.defaults().get("brute_force");
        RuleConfig m = RuleConfigs.merge(def, new RuleConfigOverride("brute_force", null, 0, null, "bogus",
                List.of(), 0));
        assertThat(m).isEqualTo(def);
    }

    @Test
    void unknownRuleFallback() {
        RuleConfig u = RuleConfigs.unknown("x");
        assertThat(u.enabled()).isTrue();
        assertThat(u.channels()).containsExactly("inapp");
        assertThat(u.cooldownMinutes()).isEqualTo(60);
        assertThat(u.severity()).isEqualTo(Severity.WARNING);
    }
}
