package rw.gov.risa.sso.mfa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.credential.CredentialModel;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.AuthDetails;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The listener's payloads, end to end over mocked Keycloak models. Same shape as the reference. */
class AuditEventListenerTest {

    KeycloakSession session;
    RealmModel realm;
    UserModel user;
    AuditEventListenerFactory forwarder;
    Map<String, String> switchCache;
    AuditEventListener listener;

    @BeforeEach
    void setUp() {
        session = mock(KeycloakSession.class);
        RealmProvider realms = mock(RealmProvider.class);
        UserProvider users = mock(UserProvider.class);
        realm = mock(RealmModel.class);
        user = mock(UserModel.class);
        when(session.realms()).thenReturn(realms);
        when(session.users()).thenReturn(users);
        when(realms.getRealm("rid")).thenReturn(realm);
        when(realm.getId()).thenReturn("rid");
        when(realm.getName()).thenReturn("government-internal");
        when(users.getUserById(realm, "uid")).thenReturn(user);
        when(user.getUsername()).thenReturn("alice");
        forwarder = mock(AuditEventListenerFactory.class);
        switchCache = new ConcurrentHashMap<>();
        listener = new AuditEventListener(session, forwarder, switchCache);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("details");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> forwarded() {
        ArgumentCaptor<Map<String, Object>> c = ArgumentCaptor.forClass(Map.class);
        verify(forwarder).forward(c.capture());
        return c.getValue();
    }

    private Event event(EventType type, Map<String, String> details) {
        Event e = new Event();
        e.setType(type);
        e.setRealmId("rid");
        e.setClientId("minagri-portal");
        e.setUserId("uid");
        e.setSessionId("sid");
        e.setIpAddress("10.0.0.1");
        e.setDetails(details);
        return e;
    }

    @Test
    void aFailedOtpIsForwardedWithTheResolvedUsernameAndCredentialType() {
        SubjectCredentialManager creds = mock(SubjectCredentialManager.class);
        when(user.credentialManager()).thenReturn(creds);
        CredentialModel otp = new CredentialModel();
        otp.setType("otp");
        when(creds.getStoredCredentialById("cid")).thenReturn(otp);
        Event e = event(EventType.LOGIN_ERROR, new HashMap<>(Map.of("selected_credential_id", "cid")));
        e.setError("invalid_user_credentials");

        listener.onEvent(e);

        Map<String, Object> p = forwarded();
        assertThat(p.keySet()).containsExactly("event_type", "user_id", "app_id", "realm", "performed_by",
                "target", "ip_address", "details", "severity");
        assertThat(p).containsEntry("event_type", "auth.mfa.otp_failed")
                .containsEntry("user_id", "alice").containsEntry("performed_by", "alice")
                .containsEntry("target", "alice").containsEntry("app_id", "minagri-portal")
                .containsEntry("realm", "government-internal").containsEntry("ip_address", "10.0.0.1")
                .containsEntry("severity", "WARNING");
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) p.get("details");
        assertThat(d.keySet()).containsExactly("source", "keycloak_event_type", "keycloak_user_id", "session",
                "error", "failed_credential_type", "keycloak_details");
        assertThat(d).containsEntry("source", "keycloak").containsEntry("keycloak_event_type", "LOGIN_ERROR")
                .containsEntry("keycloak_user_id", "uid").containsEntry("session", "sid")
                .containsEntry("error", "invalid_user_credentials")
                .containsEntry("failed_credential_type", "otp")
                .containsEntry("keycloak_details", Map.of("selected_credential_id", "cid"));
    }

    @Test
    void enrolmentUsesTheUsernameDetailAndOmitsAbsentOptionalFields() {
        listener.onEvent(event(EventType.UPDATE_TOTP, Map.of("username", "bob")));
        Map<String, Object> p = forwarded();
        assertThat(p).containsEntry("event_type", "auth.mfa.totp_enrolled").containsEntry("user_id", "bob")
                .containsEntry("severity", "INFO");
        assertThat(details(p)).doesNotContainKeys("error", "failed_credential_type");
    }

    @Test
    void unmappedEventsAreNotForwarded() {
        listener.onEvent(event(EventType.LOGIN, Map.of()));
        listener.onEvent(event(EventType.CODE_TO_TOKEN, Map.of()));
        Event noType = event(EventType.LOGIN, Map.of());
        noType.setType(null);
        listener.onEvent(noType);
        verify(forwarder, never()).forward(any());
    }

    private AdminEvent admin(ResourceType type, OperationType op, String path, String rep) {
        AdminEvent e = new AdminEvent();
        e.setRealmId("rid");
        e.setResourceType(type);
        e.setOperationType(op);
        e.setResourcePath(path);
        e.setRepresentation(rep);
        e.setTime(0L);
        AuthDetails a = new AuthDetails();
        a.setUserId("admin-uid");
        a.setClientId("admin-cli");
        a.setRealmId("master");
        a.setIpAddress("10.0.0.9");
        e.setAuthDetails(a);
        return e;
    }

    @Test
    void anAdminCredentialRemovalIsForwardedWithTheOwner() {
        listener.onEvent(admin(ResourceType.USER, OperationType.ACTION, "users/uid/credentials/c1", null), false);
        Map<String, Object> p = forwarded();
        assertThat(p).containsEntry("event_type", "admin.mfa.credential_removed")
                .containsEntry("user_id", "admin-uid").containsEntry("performed_by", "admin-uid")
                .containsEntry("app_id", "admin-cli").containsEntry("target", "alice")
                .containsEntry("ip_address", "10.0.0.9").containsEntry("severity", "WARNING");
        assertThat(details(p)).containsEntry("source", "keycloak-admin")
                .containsEntry("auth_user_id", "admin-uid").containsEntry("auth_client_id", "admin-cli")
                .containsEntry("auth_realm_id", "master")
                .containsEntry("resource_path", "users/uid/credentials/c1")
                .containsEntry("keycloak_user_id", "uid").containsEntry("credential_id", "c1");
    }

    @Test
    void anExemptRoleMappingIsForwarded() {
        listener.onEvent(admin(ResourceType.REALM_ROLE_MAPPING, OperationType.CREATE,
                "users/uid/role-mappings/realm", "[{\"name\":\"mfa-exempt\"}]"), true);
        Map<String, Object> p = forwarded();
        assertThat(p).containsEntry("event_type", "admin.mfa.exempt_role_mapping")
                .containsEntry("target", "users/uid/role-mappings/realm");
        assertThat(details(p)).containsEntry("operation", "CREATE");
    }

    @Test
    void aRealmUpdateIsReportedOnlyWhenTheEffectiveModeChanges() {
        when(realm.getAttribute("mfa_totp_mode")).thenReturn(null, "all", "OFF", "off");
        AdminEvent update = admin(ResourceType.REALM, OperationType.UPDATE, "", null);
        listener.onEvent(update, false);   // primes (null legacy -> all)
        listener.onEvent(update, false);   // all -> all
        verify(forwarder, never()).forward(any());
        listener.onEvent(update, false);   // all -> off : reported
        listener.onEvent(update, false);   // off -> off
        Map<String, Object> p = forwarded();
        verify(forwarder, times(1)).forward(any());
        assertThat(p).containsEntry("event_type", "admin.mfa.switch_observed")
                .containsEntry("target", "mfa_totp_mode");
        assertThat(details(p)).containsEntry("old_value", "all").containsEntry("new_value", "off")
                .containsEntry("timestamp", "1970-01-01T00:00:00Z")
                .containsKey("note");
        assertThat(switchCache).containsEntry("rid", "off");
    }

    @Test
    void otherAdminEventsAreIgnored() {
        listener.onEvent(admin(ResourceType.CLIENT, OperationType.UPDATE, "clients/x", "{}"), true);
        listener.onEvent(admin(ResourceType.REALM_ROLE_MAPPING, OperationType.CREATE, "users/uid/role-mappings/realm",
                "[{\"name\":\"employee\"}]"), true);
        verify(forwarder, never()).forward(any());
    }

    @Test
    void recoveryCodeUsedPayload() {
        Map<String, Object> p = AuditPayloads.recoveryCodeUsed("alice", "uid", "minagri-portal",
                "government-internal", "10.0.0.1", true);
        assertThat(p).containsEntry("event_type", "auth.mfa.recovery_code_used").containsEntry("severity", "WARNING")
                .containsEntry("user_id", "alice").containsEntry("target", "alice");
        assertThat((details(p)).keySet()).containsExactly("source", "keycloak_user_id",
                "otp_credentials_removed", "reenrolment_required");
        assertThat(List.copyOf(p.keySet())).hasSize(9);
    }
}
