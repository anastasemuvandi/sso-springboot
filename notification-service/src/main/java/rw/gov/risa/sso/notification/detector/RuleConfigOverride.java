package rw.gov.risa.sso.notification.detector;

import java.util.List;

/** A row of {@code alert_configs}: any field may be null (= not overridden). */
public record RuleConfigOverride(
        String ruleName,
        Boolean enabled,
        Integer threshold,
        Integer windowMinutes,
        String severity,
        List<String> channels,
        Integer cooldownMinutes) {
}
