package rw.gov.risa.sso.wrapper.service.ippis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisOutcome;
import rw.gov.risa.sso.wrapper.policy.ippisperson.ReconcileOutcome;
import rw.gov.risa.sso.wrapper.service.lifecycle.KeycloakUserGateway;
import rw.gov.risa.sso.wrapper.service.lifecycle.LifecycleService;
import rw.gov.risa.sso.wrapper.service.lifecycle.UserOps;

class IppisWebhookServiceTest {

    static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    static final String SECRET = "s3cr3t";
    static final byte[] TERMINATED = """
            {"event":"employee.terminated","employee":{"employee_number":"EMP1","institution":"MINAGRI","status":"terminated"}}
            """.getBytes(StandardCharsets.UTF_8);

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    KeycloakUserGateway gateway = mock(KeycloakUserGateway.class);
    UserOps ops = mock(UserOps.class);
    LifecycleService lifecycle = mock(LifecycleService.class);
    IppisReconcileService reconcile = mock(IppisReconcileService.class);
    AuditEmitter audit = mock(AuditEmitter.class);

    IppisWebhookService service(String secret) {
        return new IppisWebhookService(IppisFixtures.props(secret), redis, new ObjectMapper(), gateway, lifecycle,
                reconcile, audit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(gateway.open()).thenReturn(ops);
    }

    static String ts(Instant i) {
        return Long.toString(i.getEpochSecond());
    }

    @Test
    void offWithoutSecret() {
        assertThatThrownBy(() -> service("").receive(TERMINATED, "sha256=x", ts(NOW), "1.2.3.4"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));
        verifyNoInteractions(redis, gateway);
    }

    @Test
    void signatureIsOverTimestampPlusRawBody() {
        String sig = IppisWebhookService.sign(SECRET, ts(NOW), TERMINATED);
        assertThat(IppisWebhookService.verify(SECRET, ts(NOW), TERMINATED, sig)).isTrue();
        // the body alone (the reference's scheme) is not accepted
        assertThat(IppisWebhookService.verify(SECRET, "", TERMINATED, sig)).isFalse();
        assertThat(IppisWebhookService.verify(SECRET, ts(NOW), TERMINATED, sig.replace('a', 'b'))).isFalse();
        assertThat(IppisWebhookService.verify(SECRET, ts(NOW), TERMINATED, null)).isFalse();
    }

    @Test
    void badSignatureIs401AndNeverTouchesTheNonceStore() {
        assertThatThrownBy(() -> service(SECRET).receive(TERMINATED, "sha256=deadbeef", ts(NOW), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(401);
                    assertThat(e.error()).isEqualTo("invalid_signature");
                });
        verifyNoInteractions(values);
    }

    @Test
    void staleTimestampIsRejectedEvenWhenCorrectlySigned() {
        String old = ts(NOW.minusSeconds(301));
        String sig = IppisWebhookService.sign(SECRET, old, TERMINATED);
        assertThatThrownBy(() -> service(SECRET).receive(TERMINATED, sig, old, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("stale_timestamp"));
        String future = ts(NOW.plusSeconds(400));
        String sig2 = IppisWebhookService.sign(SECRET, future, TERMINATED);
        assertThatThrownBy(() -> service(SECRET).receive(TERMINATED, sig2, future, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("stale_timestamp"));
    }

    @Test
    void replayIsRefused() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        String sig = IppisWebhookService.sign(SECRET, ts(NOW), TERMINATED);
        assertThatThrownBy(() -> service(SECRET).receive(TERMINATED, sig, ts(NOW), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(409);
                    assertThat(e.error()).isEqualTo("replayed");
                });
        verifyNoInteractions(gateway);
    }

    @Test
    void nonceTtlCoversTheWholeSkewWindow() {
        assertThat(IppisFixtures.props(SECRET).webhook().nonceTtl()).isGreaterThanOrEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void redisDownFailsClosed() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(new IllegalStateException());
        String sig = IppisWebhookService.sign(SECRET, ts(NOW), TERMINATED);
        assertThatThrownBy(() -> service(SECRET).receive(TERMINATED, sig, ts(NOW), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));
    }

    @Test
    void terminatedEventOffboardsEveryMatchedUser() {
        var u = IppisFixtures.user("u1", "EMP1", true, Map.of());
        when(ops.findByAttribute("employee_number", "EMP1")).thenReturn(List.of(u));
        when(lifecycle.applyLeaver(eq(ops), eq(u), any(), eq("ippis-webhook"), eq(false)))
                .thenReturn(ReconcileOutcome.of("u1", IppisOutcome.DISABLED, "EMP1"));
        String sig = IppisWebhookService.sign(SECRET, ts(NOW), TERMINATED);
        Map<String, Object> out = service(SECRET).receive(TERMINATED, sig, ts(NOW), null);
        assertThat(out).containsEntry("status", "ok").containsEntry("event", "employee.terminated");
        assertThat((List<?>) out.get("results")).hasSize(1);
        verify(reconcile, never()).dispatch(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void updatedEventGoesThroughTheSyncAndUnknownEmployeeIsNoUser() {
        byte[] body = """
                {"event":"employee.updated","employee":{"employee_number":"EMP7","position":"Officer"}}
                """.getBytes(StandardCharsets.UTF_8);
        when(ops.findByAttribute("employee_number", "EMP7")).thenReturn(List.of());
        String sig = IppisWebhookService.sign(SECRET, ts(NOW), body);
        assertThat(service(SECRET).receive(body, sig, ts(NOW), null)).containsEntry("status", "no_user");
    }

    @Test
    void malformedEventIs422() {
        byte[] body = "{\"event\":\"employee.exploded\"}".getBytes(StandardCharsets.UTF_8);
        String sig = IppisWebhookService.sign(SECRET, ts(NOW), body);
        assertThatThrownBy(() -> service(SECRET).receive(body, sig, ts(NOW), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(422));
    }
}
