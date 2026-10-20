package rw.gov.risa.sso.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import rw.gov.risa.sso.notification.api.AlertConfigRead;
import rw.gov.risa.sso.notification.api.AlertConfigUpdate;
import rw.gov.risa.sso.notification.repository.AlertConfigRepository;
import rw.gov.risa.sso.notification.repository.AlertConfigRow;
import rw.gov.risa.sso.notification.web.ApiException;

class ConfigServiceTest {

    private final AlertConfigRepository repo = mock(AlertConfigRepository.class);
    private final ConfigService svc = new ConfigService(repo, TestProps.props("", "", ""),
            Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void listShowsDefaultsWithNullIdWhenNoOverride() {
        when(repo.findAll()).thenReturn(Map.of());
        List<AlertConfigRead> l = svc.list();
        assertThat(l).hasSize(6);
        assertThat(l.get(0).ruleName()).isEqualTo("brute_force");
        assertThat(l.get(0).id()).isNull();
        assertThat(l.get(0).severity()).isEqualTo("CRITICAL");
    }

    @Test
    void unknownRuleIs404() {
        assertThatThrownBy(() -> svc.update("nope", new AlertConfigUpdate()))
                .isInstanceOf(ApiException.class).hasMessage("Unknown rule: nope");
    }

    @Test
    void firstOverrideStartsFromTheRuleDefaultsNotFromWarning() {
        when(repo.find("brute_force")).thenReturn(Optional.empty());
        when(repo.upsert(any())).thenAnswer(i -> i.getArgument(0));
        AlertConfigUpdate u = new AlertConfigUpdate();
        u.setEnabled(false);
        AlertConfigRead r = svc.update("brute_force", u);
        ArgumentCaptor<AlertConfigRow> c = ArgumentCaptor.forClass(AlertConfigRow.class);
        Mockito.verify(repo).upsert(c.capture());
        assertThat(c.getValue().severity()).isEqualTo("CRITICAL");
        assertThat(c.getValue().channels()).containsExactly("email", "inapp", "webhook");
        assertThat(r.enabled()).isFalse();
        assertThat(r.cooldownMinutes()).isEqualTo(5);
    }

    @Test
    void invalidValuesAre422() {
        AlertConfigUpdate u = new AlertConfigUpdate();
        u.setSeverity("LOUD");
        u.setChannels(List.of("sms"));
        u.setCooldownMinutes(-1);
        assertThatThrownBy(() -> svc.update("brute_force", u)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status().value()).isEqualTo(422);
            assertThat(e.details()).containsKeys("severity", "channels", "cooldown_minutes");
        });
    }
}
