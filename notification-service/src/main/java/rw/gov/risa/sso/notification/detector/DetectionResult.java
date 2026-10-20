package rw.gov.risa.sso.notification.detector;

import java.util.Map;
import java.util.Objects;

/**
 * One triggered rule. {@code subjectKey} is what the per-rule cooldown is keyed on
 * (see {@link SubjectKeys}).
 */
public record DetectionResult(
        String ruleName,
        Severity severity,
        String userId,
        String ipAddress,
        String appId,
        Map<String, Object> details,
        String subjectKey) {

    public DetectionResult {
        Objects.requireNonNull(ruleName, "ruleName");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(subjectKey, "subjectKey");
        details = details == null ? Map.of() : details;
    }
}
