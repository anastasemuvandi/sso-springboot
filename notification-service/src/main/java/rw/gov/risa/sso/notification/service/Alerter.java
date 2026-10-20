package rw.gov.risa.sso.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import rw.gov.risa.sso.notification.channel.AlertMessage;
import rw.gov.risa.sso.notification.channel.Delivery;
import rw.gov.risa.sso.notification.channel.NotificationChannel;
import rw.gov.risa.sso.notification.config.NotificationProperties;
import rw.gov.risa.sso.notification.detector.AlertText;
import rw.gov.risa.sso.notification.detector.CooldownPolicy;
import rw.gov.risa.sso.notification.detector.DetectionResult;
import rw.gov.risa.sso.notification.detector.RuleConfig;
import rw.gov.risa.sso.notification.detector.RuleConfigs;
import rw.gov.risa.sso.notification.repository.AlertRepository;
import rw.gov.risa.sso.notification.repository.AlertRow;
import rw.gov.risa.sso.notification.repository.NotificationRepository;
import rw.gov.risa.sso.notification.repository.NotificationRow;

/**
 * Reference {@code process_detections}: for each result — load the rule config, skip if the
 * rule is disabled or an un-acknowledged alert for the same rule+subject is inside the
 * cooldown, persist the alert, then deliver every configured channel and record one
 * {@code notifications} row per delivery (sent, failed or skipped).
 */
@Service
public class Alerter {

    private static final Logger log = LoggerFactory.getLogger(Alerter.class);

    private final AlertRepository alerts;
    private final NotificationRepository notifications;
    private final Map<String, NotificationChannel> channels = new LinkedHashMap<>();
    private final NotificationProperties props;
    private final Clock clock;

    public Alerter(AlertRepository alerts, NotificationRepository notifications, List<NotificationChannel> channels,
                   NotificationProperties props, Clock clock) {
        this.alerts = alerts;
        this.notifications = notifications;
        channels.forEach(c -> this.channels.put(c.name(), c));
        this.props = props;
        this.clock = clock;
    }

    /** @return the number of alerts created */
    public int process(List<DetectionResult> results, Map<String, RuleConfig> configs) {
        int created = 0;
        for (DetectionResult r : results) {
            RuleConfig cfg = configs.getOrDefault(r.ruleName(), RuleConfigs.unknown(r.ruleName()));
            if (!cfg.enabled()) {
                continue;
            }
            Instant now = clock.instant();
            Instant last = alerts.lastUnacknowledgedSince(r.ruleName(), r.subjectKey(),
                    CooldownPolicy.since(now, cfg.cooldownMinutes())).orElse(null);
            if (CooldownPolicy.isSuppressed(last, now, cfg.cooldownMinutes())) {
                log.debug("Cooldown skip: {} subject={}", r.ruleName(), r.subjectKey());
                continue;
            }
            String severity = cfg.severity().name();
            AlertRow alert = new AlertRow(UUID.randomUUID(), r.ruleName(), severity, r.userId(), r.ipAddress(),
                    r.appId(), r.subjectKey(), r.details(), false, null, null, now);
            alerts.insert(alert);
            dispatch(alert, cfg.channels());
            created++;
            log.info("Alert created: {} [{}] id={}", r.ruleName(), severity, alert.id());
        }
        return created;
    }

    private void dispatch(AlertRow alert, List<String> channelNames) {
        String title = AlertText.title(alert.severity(), alert.ruleName());
        String message = AlertText.message(alert.ruleName(), alert.userId(), alert.ipAddress(), alert.appId(),
                alert.details());
        AlertMessage msg = new AlertMessage(alert.id(), alert.ruleName(), alert.severity(), title, message,
                alert.userId(), alert.ipAddress(), alert.appId(), alert.details());
        List<String> admins = props.adminEmailList();
        for (String name : channelNames) {
            NotificationChannel ch = channels.get(name);
            if (ch == null) {
                log.warn("Unknown channel '{}' for rule {}", name, alert.ruleName());
                continue;
            }
            List<Delivery> deliveries;
            try {
                deliveries = ch.deliver(msg, admins);
            } catch (RuntimeException e) {
                deliveries = List.of(Delivery.failed(name, "", e));
            }
            Instant at = clock.instant();
            for (Delivery d : deliveries) {
                notifications.insert(new NotificationRow(UUID.randomUUID(), alert.id(), d.channel(),
                        d.recipient() == null ? "" : d.recipient(), title, message, false, d.status(), d.error(),
                        Delivery.SENT.equals(d.status()) ? at : null, at));
            }
        }
    }
}
