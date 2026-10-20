package rw.gov.risa.sso.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;
import org.thymeleaf.TemplateEngine;
import rw.gov.risa.sso.notification.channel.AlertMessage;
import rw.gov.risa.sso.notification.channel.Delivery;
import rw.gov.risa.sso.notification.channel.EmailChannel;
import rw.gov.risa.sso.notification.channel.InAppChannel;
import rw.gov.risa.sso.notification.channel.NotificationChannel;
import rw.gov.risa.sso.notification.channel.WebhookChannel;
import rw.gov.risa.sso.notification.config.NotificationProperties;
import rw.gov.risa.sso.notification.detector.DetectionResult;
import rw.gov.risa.sso.notification.detector.RuleConfig;
import rw.gov.risa.sso.notification.detector.RuleConfigOverride;
import rw.gov.risa.sso.notification.detector.RuleConfigs;
import rw.gov.risa.sso.notification.detector.Severity;
import rw.gov.risa.sso.notification.repository.AlertRepository;
import rw.gov.risa.sso.notification.repository.AlertRow;
import rw.gov.risa.sso.notification.repository.NotificationRepository;
import rw.gov.risa.sso.notification.repository.NotificationRow;

class AlerterTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private AlertRepository alerts;
    private NotificationRepository notifications;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        alerts = mock(AlertRepository.class);
        notifications = mock(NotificationRepository.class);
        when(alerts.lastUnacknowledgedSince(anyString(), anyString(), any())).thenReturn(Optional.empty());
    }

    private Alerter alerter(NotificationProperties p, List<NotificationChannel> channels) {
        return new Alerter(alerts, notifications, channels, p, clock);
    }

    private static List<NotificationChannel> realChannels(NotificationProperties p) {
        return List.of(new EmailChannel(p, new TemplateEngine()), new InAppChannel(),
                new WebhookChannel(p, RestClient.builder()));
    }

    private static DetectionResult bruteForce() {
        return new DetectionResult("brute_force", Severity.CRITICAL, "alice", "10.0.0.1", null,
                Map.of("count", 5), "user:alice");
    }

    private List<NotificationRow> rows() {
        ArgumentCaptor<NotificationRow> c = ArgumentCaptor.forClass(NotificationRow.class);
        verify(notifications, org.mockito.Mockito.atLeast(0)).insert(c.capture());
        return c.getAllValues();
    }

    @Test
    void unconfiguredChannelsAreRecordedAsSkipped() {
        NotificationProperties p = TestProps.props("a@gov.rw,b@gov.rw", "", "");
        int n = alerter(p, realChannels(p)).process(List.of(bruteForce()), RuleConfigs.defaults());
        assertThat(n).isEqualTo(1);
        List<NotificationRow> rows = rows();
        assertThat(rows).extracting(NotificationRow::channel, NotificationRow::status).containsExactly(
                org.assertj.core.groups.Tuple.tuple("email", "skipped"),
                org.assertj.core.groups.Tuple.tuple("inapp", "sent"),
                org.assertj.core.groups.Tuple.tuple("inapp", "sent"),
                org.assertj.core.groups.Tuple.tuple("webhook", "skipped"));
        assertThat(rows.get(0).error()).contains("SMTP_HOST");
        assertThat(rows.get(3).error()).contains("WEBHOOK_URL");
        assertThat(rows.get(0).sentAt()).isNull();
        assertThat(rows.get(1).sentAt()).isEqualTo(NOW);
        assertThat(rows.get(1).title()).isEqualTo("[CRITICAL] SSO Alert — Brute Force");
        assertThat(rows.get(1).message()).isEqualTo("User: alice; IP: 10.0.0.1; count: 5");
    }

    @Test
    void noAdminEmailsRecordsInAppAsSkipped() {
        NotificationProperties p = TestProps.props("", "", "");
        alerter(p, realChannels(p)).process(List.of(bruteForce()), RuleConfigs.defaults());
        assertThat(rows()).filteredOn(r -> r.channel().equals("inapp"))
                .singleElement().extracting(NotificationRow::status).isEqualTo("skipped");
    }

    @Test
    void cooldownSuppressesTheAlert() {
        when(alerts.lastUnacknowledgedSince(eq("brute_force"), eq("user:alice"), eq(NOW.minusSeconds(300))))
                .thenReturn(Optional.of(NOW.minusSeconds(60)));
        NotificationProperties p = TestProps.props("a@gov.rw", "", "");
        assertThat(alerter(p, realChannels(p)).process(List.of(bruteForce()), RuleConfigs.defaults())).isZero();
        verify(alerts, never()).insert(any());
    }

    @Test
    void disabledRuleCreatesNothing() {
        Map<String, RuleConfig> cfg = new java.util.LinkedHashMap<>(RuleConfigs.defaults());
        cfg.put("brute_force", RuleConfigs.merge(cfg.get("brute_force"),
                new RuleConfigOverride("brute_force", false, null, null, null, null, null)));
        NotificationProperties p = TestProps.props("a@gov.rw", "", "");
        assertThat(alerter(p, realChannels(p)).process(List.of(bruteForce()), cfg)).isZero();
        verify(alerts, never()).insert(any());
    }

    @Test
    void configuredSeverityAndChannelsWin() {
        Map<String, RuleConfig> cfg = new java.util.LinkedHashMap<>(RuleConfigs.defaults());
        cfg.put("brute_force", RuleConfigs.merge(cfg.get("brute_force"),
                new RuleConfigOverride("brute_force", true, null, null, "INFO", List.of("inapp"), null)));
        NotificationProperties p = TestProps.props("a@gov.rw", "", "");
        alerter(p, realChannels(p)).process(List.of(bruteForce()), cfg);
        ArgumentCaptor<AlertRow> a = ArgumentCaptor.forClass(AlertRow.class);
        verify(alerts).insert(a.capture());
        assertThat(a.getValue().severity()).isEqualTo("INFO");
        assertThat(a.getValue().subjectKey()).isEqualTo("user:alice");
        assertThat(rows()).extracting(NotificationRow::channel).containsExactly("inapp");
    }

    @Test
    void aThrowingChannelIsRecordedAsFailedAndOthersStillRun() {
        NotificationChannel boom = new NotificationChannel() {
            public String name() {
                return "email";
            }

            public List<Delivery> deliver(AlertMessage alert, List<String> admins) {
                throw new IllegalStateException("smtp down");
            }
        };
        NotificationProperties p = TestProps.props("a@gov.rw", "", "");
        alerter(p, List.of(boom, new InAppChannel())).process(List.of(bruteForce()), RuleConfigs.defaults());
        assertThat(rows()).extracting(NotificationRow::channel, NotificationRow::status).contains(
                org.assertj.core.groups.Tuple.tuple("email", "failed"),
                org.assertj.core.groups.Tuple.tuple("inapp", "sent"));
        verify(notifications, times(2)).insert(any());
    }
}
