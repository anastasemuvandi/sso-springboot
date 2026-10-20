package rw.gov.risa.sso.notification.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import rw.gov.risa.sso.notification.api.AlertConfigRead;
import rw.gov.risa.sso.notification.api.AlertConfigUpdate;
import rw.gov.risa.sso.notification.config.NotificationProperties;
import rw.gov.risa.sso.notification.detector.Channel;
import rw.gov.risa.sso.notification.detector.RuleConfig;
import rw.gov.risa.sso.notification.detector.RuleConfigOverride;
import rw.gov.risa.sso.notification.detector.RuleConfigs;
import rw.gov.risa.sso.notification.detector.Severity;
import rw.gov.risa.sso.notification.repository.AlertConfigRepository;
import rw.gov.risa.sso.notification.repository.AlertConfigRow;
import rw.gov.risa.sso.notification.web.ApiException;

/** Rule configuration: built-in defaults with {@code alert_configs} overrides on top. */
@Service
public class ConfigService {

    private final AlertConfigRepository repo;
    private final Map<String, RuleConfig> defaults;
    private final Clock clock;

    public ConfigService(AlertConfigRepository repo, NotificationProperties props, Clock clock) {
        this.repo = repo;
        this.clock = clock;
        this.defaults = RuleConfigs.defaults(props.criticalCooldownMinutes(), props.warningCooldownMinutes());
    }

    public Map<String, RuleConfig> defaults() {
        return defaults;
    }

    /** Effective config per rule (reference {@code _get_config} for every rule). */
    public Map<String, RuleConfig> effective() {
        Map<String, AlertConfigRow> rows = repo.findAll();
        Map<String, RuleConfig> out = new LinkedHashMap<>();
        defaults.forEach((rule, def) -> out.put(rule, RuleConfigs.merge(def, override(rows.get(rule)))));
        return out;
    }

    /** {@code GET /config}: one entry per known rule, in the reference's order. */
    public List<AlertConfigRead> list() {
        Map<String, AlertConfigRow> rows = repo.findAll();
        List<AlertConfigRead> out = new ArrayList<>();
        defaults.forEach((rule, def) -> out.add(read(def, rows.get(rule))));
        return out;
    }

    /** {@code PUT /config/{rule_name}}: partial update, creating the override row on first use. */
    public AlertConfigRead update(String ruleName, AlertConfigUpdate u) {
        RuleConfig def = defaults.get(ruleName);
        if (def == null) {
            throw ApiException.notFound("Unknown rule: " + ruleName);
        }
        validate(u);
        // A new row starts from the rule's defaults. (The reference started from its ORM
        // defaults — severity WARNING, channels [] — so disabling brute_force once silently
        // downgraded it to WARNING when it was re-enabled.)
        AlertConfigRow base = repo.find(ruleName).orElseGet(() -> new AlertConfigRow(UUID.randomUUID(), ruleName,
                def.enabled(), def.threshold(), def.windowMinutes(), def.severity().name(), def.channels(),
                def.cooldownMinutes(), null));
        AlertConfigRow next = new AlertConfigRow(
                base.id(), ruleName,
                u.has("enabled") ? u.getEnabled() : base.enabled(),
                u.has("threshold") ? u.getThreshold() : base.threshold(),
                u.has("window_minutes") ? u.getWindowMinutes() : base.windowMinutes(),
                u.has("severity") ? normalizeSeverity(u.getSeverity()) : base.severity(),
                u.has("channels") ? u.getChannels() : base.channels(),
                u.has("cooldown_minutes") ? u.getCooldownMinutes() : base.cooldownMinutes(),
                clock.instant());
        return read(def, repo.upsert(next));
    }

    private static AlertConfigRead read(RuleConfig def, AlertConfigRow row) {
        RuleConfig eff = RuleConfigs.merge(def, override(row));
        return new AlertConfigRead(row == null ? null : row.id(), def.ruleName(), eff.enabled(), eff.threshold(),
                eff.windowMinutes(), eff.severity().name(), eff.channels(), eff.cooldownMinutes(),
                row == null ? null : row.updatedAt());
    }

    static RuleConfigOverride override(AlertConfigRow r) {
        if (r == null) {
            return null;
        }
        return new RuleConfigOverride(r.ruleName(), r.enabled(), r.threshold(), r.windowMinutes(), r.severity(),
                r.channels(), r.cooldownMinutes());
    }

    private static String normalizeSeverity(String s) {
        return s == null ? null : Severity.parse(s).map(Enum::name).orElse(null);
    }

    static void validate(AlertConfigUpdate u) {
        Map<String, Object> errors = new LinkedHashMap<>();
        if (u.has("enabled") && u.getEnabled() == null) {
            errors.put("enabled", "must be true or false");
        }
        if (u.has("severity") && u.getSeverity() != null && Severity.parse(u.getSeverity()).isEmpty()) {
            errors.put("severity", "must be one of CRITICAL, WARNING, INFO");
        }
        if (u.has("channels") && u.getChannels() != null) {
            for (String c : u.getChannels()) {
                if (Channel.parse(c).isEmpty()) {
                    errors.put("channels", "each channel must be one of email, inapp, webhook");
                }
            }
        }
        nonNegative(errors, u, "threshold", u.getThreshold());
        nonNegative(errors, u, "window_minutes", u.getWindowMinutes());
        nonNegative(errors, u, "cooldown_minutes", u.getCooldownMinutes());
        if (!errors.isEmpty()) {
            throw ApiException.validation("Invalid rule configuration", errors);
        }
    }

    private static void nonNegative(Map<String, Object> errors, AlertConfigUpdate u, String field, Integer v) {
        if (u.has(field) && v != null && v < 0) {
            errors.put(field, "must be >= 0");
        }
    }
}
