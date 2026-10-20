package rw.gov.risa.sso.wrapper.service.setuplink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.setuplink.Delivery;
import rw.gov.risa.sso.wrapper.policy.setuplink.InMemorySetupLinkStore;
import rw.gov.risa.sso.wrapper.policy.setuplink.SetupLinkPolicy;
import rw.gov.risa.sso.wrapper.policy.setuplink.SetupLinks;
import rw.gov.risa.sso.wrapper.policy.setuplink.TokenSource;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessServiceTest;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.service.mail.DeliveryResult;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;

class SetupLinkServiceTest {

    RealmResource realm = mock(RealmResource.class, RETURNS_DEEP_STUBS);
    KeycloakExecutors executors = mock(KeycloakExecutors.class);
    KeycloakDirectory directory = mock(KeycloakDirectory.class);
    Mailer mailer = mock(Mailer.class);
    AuditEmitter audit = mock(AuditEmitter.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    Clock clock = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC);
    InMemorySetupLinkStore store = new InMemorySetupLinkStore(clock);
    SetupLinks links = new SetupLinks(store, TokenSource.secure(), clock);
    SetupLinkService service;
    UserRepresentation pending;

    @BeforeEach
    void setUp() {
        when(executors.serviceAccount()).thenReturn(KeycloakExec.of(realm));
        when(redis.opsForValue()).thenReturn(ops);
        service = new SetupLinkService(executors, directory, links, mailer, mock(ClientAccessService.class),
                ClientAccessServiceTest.sso(), audit, redis, clock);
        pending = new UserRepresentation();
        pending.setId("uid");
        pending.setUsername("ada");
        pending.setEmail("ada@gov.rw");
        pending.setEnabled(true);
        pending.setRequiredActions(List.of(SetupLinkPolicy.UPDATE_PASSWORD));
        when(directory.getUser(any(), eq("uid"))).thenReturn(Optional.of(pending));
        when(directory.credentialTypes(any(), eq("uid"))).thenReturn(List.of("password"));
    }

    @Test
    void urlCarriesTheTokenAndOnlyTheHashIsStored() {
        var issued = service.issue("uid", List.of("app"));
        assertThat(service.setupUrl(issued.token())).isEqualTo("http://w/account/setup?token=" + issued.token());
        assertThat(store.findRecord(SetupLinkPolicy.tokenHash(issued.token()))).isPresent();
        assertThat(issued.record().hash()).isNotEqualTo(issued.token());
    }

    @Test
    void unknownLinkIsRefusedAndAudited() {
        var c = service.complete("nope", "Secret-123456", "Secret-123456", null);
        assertThat(c.outcome()).isEqualTo(SetupLinkPolicy.LINK_UNKNOWN);
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.SETUP_LINK_USED
                && "failure".equals(e.outcome())));
    }

    @Test
    void mismatchedPasswordsAndKeycloakRefusalDoNotSpend() {
        String token = service.issue("uid", List.of()).token();
        assertThat(service.complete(token, "a", "b", null).outcome()).isEqualTo(SetupLinkPolicy.PASSWORDS_DIFFER);
        UserResource ur = mock(UserResource.class);
        when(realm.users().get("uid")).thenReturn(ur);
        org.mockito.Mockito.doThrow(new BadRequestException(Response.status(400)
                .entity("{\"error\":\"invalidPasswordMinLengthMessage\",\"error_description\":\"Invalid password: minimum length 10.\"}")
                .build())).when(ur).resetPassword(any());
        var c = service.complete(token, "short", "short", null);
        assertThat(c.outcome()).isEqualTo(SetupLinkPolicy.PASSWORD_REJECTED);
        assertThat(c.message()).isEqualTo("Minimum length 10.");
        assertThat(service.open(token).usable()).isTrue();
        verify(ur, never()).update(any());
    }

    @Test
    void setPasswordRefusalIs400PasswordRejected() {
        UserResource ur = mock(UserResource.class);
        when(realm.users().get("uid")).thenReturn(ur);
        org.mockito.Mockito.doThrow(new BadRequestException()).when(ur).resetPassword(any());
        assertThatThrownBy(() -> service.setPassword("uid", "x"))
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(400);
                    assertThat(((ApiException) e).error()).isEqualTo("password_rejected");
                    assertThat(e.getMessage()).isEqualTo(SetupLinkPolicy.DEFAULT_PASSWORD_ERROR);
                });
    }

    @Test
    void holderResendOutcomesAreRealButNeverThrown() {
        assertThat(service.requestResend("  ", null)).isEqualTo(SetupLinkPolicy.INVALID_IDENTIFIER);
        when(directory.findUserByUsername(any(), eq("ghost"))).thenReturn(Optional.empty());
        when(directory.findUsersByEmail(any(), eq("ghost"), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        assertThat(service.requestResend("ghost", null)).isEqualTo(SetupLinkPolicy.NO_ACCOUNT);

        when(directory.findUserByUsername(any(), eq("ada"))).thenReturn(Optional.of(pending));
        when(directory.findUsersByEmail(any(), eq("ada"), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(mailer.sendSetupLink(any(), eq(SetupLinkPolicy.VIA_HOLDER))).thenReturn(DeliveryResult.failed("down"));
        assertThat(service.requestResend("ADA", null)).isEqualTo(SetupLinkPolicy.SEND_FAILED);
        when(mailer.sendSetupLink(any(), eq(SetupLinkPolicy.VIA_HOLDER))).thenReturn(DeliveryResult.sent());
        assertThat(service.requestResend("ada", null)).isEqualTo(SetupLinkPolicy.SENT);
        assertThat(service.requestResend("ada", null)).isEqualTo(SetupLinkPolicy.COOLDOWN);

        when(directory.findUserByUsername(any(), eq("boom"))).thenThrow(new RuntimeException("kc down"));
        assertThat(service.requestResend("boom", null)).isEqualTo(SetupLinkPolicy.SEND_FAILED);
    }

    @Test
    void adminResendOnlyAfterAFailedDeliveryAndRateLimited() {
        when(ops.increment(anyString())).thenReturn(1L);
        assertThat(service.adminResend("uid", "admin", "minagri", null).outcome())
                .isEqualTo(SetupLinkPolicy.LAST_EMAIL_DELIVERED);

        store.saveDelivery("uid", new Delivery("not_configured", "provisioning", "t"), Duration.ofDays(1));
        when(mailer.configured()).thenReturn(false);
        assertThat(service.adminResend("uid", "admin", "minagri", null).outcome())
                .isEqualTo(SetupLinkPolicy.MAIL_NOT_CONFIGURED);

        when(mailer.configured()).thenReturn(true);
        when(mailer.sendSetupLink(any(), eq(SetupLinkPolicy.VIA_ADMIN))).thenReturn(DeliveryResult.sent());
        assertThat(service.adminResend("uid", "admin", "minagri", "1.1.1.1").sent()).isTrue();
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.SETUP_LINK_ADMIN_RESEND
                && "success".equals(e.outcome()) && "minagri".equals(e.extra().get("institution"))));

        when(ops.increment(anyString())).thenReturn(11L);
        assertThatThrownBy(() -> service.adminResend("uid", "admin", "minagri", null))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(429));
    }

    @Test
    void storeRecordRoundTrips() {
        var issued = links.issue("uid", List.of("a", "b"));
        String json = RedisSetupLinkStore.writeRecord(issued.record());
        assertThat(RedisSetupLinkStore.parseRecord(json)).contains(issued.record());
        assertThat(RedisSetupLinkStore.parseRecord("garbage")).isEmpty();
        assertThat(json).doesNotContain(issued.token());
        assertThat(Map.of("k", RedisSetupLinkStore.dayKey("u", java.time.LocalDate.of(2026, 10, 20))))
                .containsValue("setup-link:day:u:20261020");
    }
}
