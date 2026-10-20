package rw.gov.risa.sso.notification.detector;

import java.util.List;
import java.util.Objects;

/** Effective configuration of one rule (defaults merged with the DB override). */
public record RuleConfig(
        String ruleName,
        boolean enabled,
        Integer threshold,
        Integer windowMinutes,
        Severity severity,
        List<String> channels,
        int cooldownMinutes) {

    public RuleConfig {
        Objects.requireNonNull(ruleName, "ruleName");
        Objects.requireNonNull(severity, "severity");
        channels = channels == null ? List.of() : List.copyOf(channels);
    }

    public int thresholdOr(int fallback) {
        return threshold == null ? fallback : threshold;
    }

    public int windowOr(int fallback) {
        return windowMinutes == null ? fallback : windowMinutes;
    }
}
