package rw.gov.risa.sso.wrapper.service.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import rw.gov.risa.sso.mfa.policy.MfaConstants;
import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;

class MfaBulkEnrolmentTest {

    RealmResource realm = mock(RealmResource.class, RETURNS_DEEP_STUBS);
    KeycloakExecutors executors = mock(KeycloakExecutors.class);
    KeycloakDirectory directory = mock(KeycloakDirectory.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    MfaBulkEnrolment bulk;

    static UserRepresentation user(String id, String username, boolean enabled, List<String> actions) {
        UserRepresentation u = new UserRepresentation();
        u.setId(id);
        u.setUsername(username);
        u.setEnabled(enabled);
        u.setRequiredActions(actions);
        return u;
    }

    @BeforeEach
    void setUp() {
        when(executors.serviceAccount()).thenReturn(KeycloakExec.of(realm));
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        bulk = new MfaBulkEnrolment(executors, directory, redis, mock(AuditEmitter.class));
    }

    @Test
    void assignsOnlyThoseWhoNeedItAndNeverTheExempt() {
        when(directory.realmRoleUsers(realm, MfaConstants.EXEMPT_ROLE)).thenReturn(List.of(user("ex", "ex", true, List.of())));
        when(directory.listUsers(realm, 0, MfaBulkEnrolment.PAGE_SIZE, true)).thenReturn(List.of(
                user("a", "alice", true, List.of()),
                user("ex", "ex", true, List.of()),
                user("off", "off", false, List.of()),
                user("svc", "service-account-x", true, List.of()),
                user("otp", "otp", true, List.of()),
                user("pend", "pend", true, List.of(MfaConstants.CONFIGURE_TOTP))));
        when(directory.credentialTypes(any(), anyString())).thenReturn(List.of("password"));
        when(directory.credentialTypes(realm, "otp")).thenReturn(List.of("password", "otp"));
        UserResource alice = mock(UserResource.class);
        when(realm.users().get("a")).thenReturn(alice);
        when(alice.toRepresentation()).thenReturn(user("a", "alice", true, List.of()));

        var s = bulk.run(false);
        assertThat(s.assigned).isEqualTo(1);
        assertThat(s.skipped).containsEntry("exempt", 1).containsEntry("disabled", 1)
                .containsEntry("service_account", 1).containsEntry("already_enrolled", 1)
                .containsEntry("already_pending", 1);
        assertThat(s.finished).isTrue();
        verify(alice).update(org.mockito.ArgumentMatchers.argThat(u -> u.getRequiredActions()
                .contains(MfaConstants.CONFIGURE_TOTP)));
        verify(ops, org.mockito.Mockito.atLeastOnce()).set(eq(MfaBulkEnrolment.CHECKPOINT_KEY), anyString(),
                any(Duration.class));
        verify(redis).delete(MfaBulkEnrolment.LOCK_KEY);
    }

    @Test
    void resumesFromTheCheckpointAndDryRunWritesNothing() {
        when(ops.get(MfaBulkEnrolment.CHECKPOINT_KEY)).thenReturn(
                "{\"examined\":100,\"assigned\":7,\"skipped\":{},\"errors\":[],\"next_offset\":100,\"finished\":false}");
        when(directory.listUsers(realm, 100, MfaBulkEnrolment.PAGE_SIZE, true))
                .thenReturn(List.of(user("z", "zed", true, List.of())));
        when(directory.credentialTypes(any(), anyString())).thenReturn(List.of());
        var s = bulk.run(true);
        assertThat(s.examined).isEqualTo(101);
        assertThat(s.assigned).isEqualTo(8);
        assertThat(s.nextOffset).isEqualTo(101);
        verify(directory, never()).listUsers(eq(realm), eq(0), anyInt(), eq(true));
        verify(realm.users(), never()).get("z");
    }

    @Test
    void secondConcurrentRunIsRefused() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThat(bulk.startAsync("root")).containsEntry("started", false);
        assertThat(bulk.run(false).errors).isNotEmpty();
    }
}
