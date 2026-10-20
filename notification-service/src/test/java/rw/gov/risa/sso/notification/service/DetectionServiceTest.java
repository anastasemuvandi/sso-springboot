package rw.gov.risa.sso.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import rw.gov.risa.sso.notification.audit.AuditClient;
import rw.gov.risa.sso.notification.detector.AuditEvent;
import rw.gov.risa.sso.notification.detector.DetectionResult;
import rw.gov.risa.sso.notification.detector.RuleConfigs;
import rw.gov.risa.sso.notification.repository.DetectorStateRepository;

class DetectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private AuditClient audit;
    private DetectorStateRepository state;
    private ConfigService configs;
    private Alerter alerter;
    private DetectionService svc;

    @BeforeEach
    void setUp() {
        audit = mock(AuditClient.class);
        state = mock(DetectorStateRepository.class);
        configs = mock(ConfigService.class);
        alerter = mock(Alerter.class);
        when(configs.effective()).thenReturn(RuleConfigs.defaults());
        when(state.alreadyProcessed(any())).thenReturn(Set.of());
        svc = new DetectionService(audit, state, configs, alerter, TestProps.props("a@gov.rw", "", ""),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void firstRunLooksBackSixtyMinutesPlusContextAndMovesWatermark() {
        when(state.watermark("detector")).thenReturn(Optional.empty());
        when(audit.fetchEvents(any(), any())).thenReturn(List.of());
        DetectionService.RunResult r = svc.runOnce();
        Instant w0 = NOW.minus(Duration.ofMinutes(60));
        verify(audit).fetchEvents(w0.minus(Duration.ofMinutes(30)), NOW.minusSeconds(2));
        verify(state).save("detector", NOW.minusSeconds(2), NOW, 0, 0);
        assertThat(r.watermark()).isEqualTo(NOW.minusSeconds(2));
    }

    @Test
    void contextOnlyEventsAndAlreadyProcessedEventsAreNotNew() {
        Instant w = NOW.minus(Duration.ofMinutes(1));
        when(state.watermark("detector")).thenReturn(Optional.of(w));
        AuditEvent context = new AuditEvent("old", "auth.login.failed", "a", "1", null, w.minus(Duration.ofMinutes(10)));
        AuditEvent overlapSeen = new AuditEvent("seen", "auth.login.failed", "a", "1", null, w.minusSeconds(30));
        AuditEvent fresh = new AuditEvent("fresh", "auth.login.failed", "a", "1", null, w.plusSeconds(10));
        when(audit.fetchEvents(any(), any())).thenReturn(List.of(context, overlapSeen, fresh));
        when(state.alreadyProcessed(any())).thenReturn(Set.of("seen"));
        DetectionService.RunResult r = svc.runOnce();
        assertThat(r.newEvents()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditEvent>> marked = ArgumentCaptor.forClass(List.class);
        verify(state).markProcessed(marked.capture(), eq(NOW));
        assertThat(marked.getValue()).extracting(AuditEvent::id).containsExactly("fresh");
    }

    @Test
    void auditOutageAbortsWithoutMovingTheWatermark() {
        when(state.watermark("detector")).thenReturn(Optional.of(NOW.minusSeconds(60)));
        when(audit.fetchEvents(any(), any())).thenThrow(new IllegalStateException("audit down"));
        assertThatThrownBy(() -> svc.runOnce()).isInstanceOf(IllegalStateException.class);
        verify(state, never()).save(anyString(), any(), any(), anyInt(), anyInt());
        verify(state, never()).markProcessed(anyList(), any());
    }

    @Test
    void historyFailureMeansNotFirstAccess() {
        when(state.watermark("detector")).thenReturn(Optional.of(NOW.minusSeconds(60)));
        AuditEvent login = new AuditEvent("l1", "auth.login.success", "bob", "1.1.1.1", "app",
                NOW.minusSeconds(30).plus(Duration.ofHours(-0)));
        // 10:00 UTC = 12:00 Kigali: no unusual-hours noise
        when(audit.fetchEvents(any(), any())).thenReturn(List.of(login));
        when(audit.countLogins(anyString(), anyString(), any(), anyInt())).thenThrow(new RuntimeException("x"));
        svc.runOnce();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DetectionResult>> results = ArgumentCaptor.forClass(List.class);
        verify(alerter).process(results.capture(), any());
        assertThat(results.getValue()).isEmpty();
    }

    @Test
    void firstAccessIsAskedUpToTheLoginWithLimitThresholdPlusOne() {
        when(state.watermark("detector")).thenReturn(Optional.of(NOW.minusSeconds(60)));
        AuditEvent login = new AuditEvent("l1", "auth.login.success", "bob", "1.1.1.1", "app", NOW.minusSeconds(30));
        when(audit.fetchEvents(any(), any())).thenReturn(List.of(login));
        when(audit.countLogins("bob", "app", NOW.minusSeconds(30), 2)).thenReturn(1);
        svc.runOnce();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DetectionResult>> results = ArgumentCaptor.forClass(List.class);
        verify(alerter).process(results.capture(), any());
        assertThat(results.getValue()).extracting(DetectionResult::ruleName).containsExactly("anomalous_access");
    }

    @Test
    void nothingToDoWhenTheClockHasNotPassedTheWatermark() {
        when(state.watermark("detector")).thenReturn(Optional.of(NOW));
        svc.runOnce();
        verify(audit, never()).fetchEvents(any(), any());
    }
}
