package rw.gov.risa.sso.notification.detector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in rule defaults ({@code DEFAULT_CONFIGS} in the reference detector) and the
 * merge rule that applies an {@code alert_configs} row over them.
 */
public final class RuleConfigs {

    private static final List<String> ALL_CHANNELS = List.of("email", "inapp", "webhook");
    private static final List<String> INAPP = List.of("inapp");

    private RuleConfigs() {
    }

    /**
     * Defaults, in the reference's order. {@code criticalCooldown}/{@code warningCooldown}
     * are the reference's {@code CRITICAL_COOLDOWN_MINUTES}/{@code WARNING_COOLDOWN_MINUTES}
     * (5/60, the same numbers its table hard-codes for the CRITICAL rules and for
     * {@code unusual_hours}); {@code anomalous_access} keeps its fixed 1440 (once a day).
     */
    public static Map<String, RuleConfig> defaults(int criticalCooldown, int warningCooldown) {
        Map<String, RuleConfig> m = new LinkedHashMap<>();
        m.put(RuleNames.BRUTE_FORCE, new RuleConfig(RuleNames.BRUTE_FORCE, true, 5, 10,
                Severity.CRITICAL, ALL_CHANNELS, criticalCooldown));
        // window_minutes 2 is the reference's per-cycle look-back; with the watermark,
        // every event is looked at exactly once, so the window is informational.
        m.put(RuleNames.UNUSUAL_HOURS, new RuleConfig(RuleNames.UNUSUAL_HOURS, true, 1, 2,
                Severity.WARNING, INAPP, warningCooldown));
        m.put(RuleNames.IMPOSSIBLE_TRAVEL, new RuleConfig(RuleNames.IMPOSSIBLE_TRAVEL, true, 2, 30,
                Severity.CRITICAL, ALL_CHANNELS, criticalCooldown));
        m.put(RuleNames.ANOMALOUS_ACCESS, new RuleConfig(RuleNames.ANOMALOUS_ACCESS, true, 1, 2,
                Severity.WARNING, INAPP, 1440));
        m.put(RuleNames.STALE_SESSION, new RuleConfig(RuleNames.STALE_SESSION, false, 1, 720,
                Severity.WARNING, INAPP, 120));
        m.put(RuleNames.MULTIPLE_SESSIONS, new RuleConfig(RuleNames.MULTIPLE_SESSIONS, false, 3, 0,
                Severity.WARNING, INAPP, 60));
        return Collections.unmodifiableMap(m);
    }

    /** Defaults with the reference's 5/60 cooldowns. */
    public static Map<String, RuleConfig> defaults() {
        return defaults(5, 60);
    }

    /**
     * Reference {@code _get_config}: with no row, the defaults; with a row, {@code enabled}
     * is taken as-is and every other field uses Python {@code or} semantics, i.e. a null,
     * zero or empty override falls back to the default.
     */
    public static RuleConfig merge(RuleConfig defaults, RuleConfigOverride row) {
        if (row == null) {
            return defaults;
        }
        boolean enabled = row.enabled() != null ? row.enabled() : defaults.enabled();
        List<String> channels = row.channels() != null && !row.channels().isEmpty()
                ? row.channels() : defaults.channels();
        int cooldown = row.cooldownMinutes() != null && row.cooldownMinutes() != 0
                ? row.cooldownMinutes() : defaults.cooldownMinutes();
        Severity severity = Severity.parse(row.severity()).orElse(defaults.severity());
        Integer threshold = row.threshold() != null && row.threshold() != 0
                ? row.threshold() : defaults.threshold();
        Integer window = row.windowMinutes() != null && row.windowMinutes() != 0
                ? row.windowMinutes() : defaults.windowMinutes();
        return new RuleConfig(defaults.ruleName(), enabled, threshold, window, severity, channels, cooldown);
    }

    /** Fallback for a rule name with no defaults (reference: enabled, inapp, 60 min, WARNING). */
    public static RuleConfig unknown(String ruleName) {
        return new RuleConfig(ruleName, true, 1, null, Severity.WARNING, INAPP, 60);
    }
}
