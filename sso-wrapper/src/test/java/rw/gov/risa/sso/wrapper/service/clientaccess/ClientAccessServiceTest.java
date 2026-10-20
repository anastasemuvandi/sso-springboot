package rw.gov.risa.sso.wrapper.service.clientaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.http.HttpStatus;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessPolicy;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessReason;
import rw.gov.risa.sso.wrapper.policy.clientaccess.CanonicalPolicies;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.RevokeTarget;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;

public class ClientAccessServiceTest {

    RealmResource realm = mock(RealmResource.class);
    KeycloakExec exec = KeycloakExec.of(realm);
    KeycloakExecutors executors = mock(KeycloakExecutors.class);
    KeycloakDirectory directory = mock(KeycloakDirectory.class);
    AccessPolicyStore store = mock(AccessPolicyStore.class);
    AuditEmitter audit = mock(AuditEmitter.class);
    ClientAccessService service;

    public static SsoProperties sso() {
        return new SsoProperties(false, "test", "http://w", new SsoProperties.Keycloak("http://kc", "http://kc",
                "http://kc", "r", "sso-client", "s", List.of("sso-client", "sso-admin-ui", "account")),
                new SsoProperties.Cors(List.of()));
    }

    static ClientRepresentation client(String clientId) {
        ClientRepresentation c = new ClientRepresentation();
        c.setClientId(clientId);
        c.setId("uuid-" + clientId);
        return c;
    }

    static UserRepresentation user(String id, String username) {
        UserRepresentation u = new UserRepresentation();
        u.setId(id);
        u.setUsername(username);
        return u;
    }

    static GroupRepresentation group(String id, String name) {
        GroupRepresentation g = new GroupRepresentation();
        g.setId(id);
        g.setName(name);
        return g;
    }

    @BeforeEach
    void setUp() {
        when(executors.serviceAccount()).thenReturn(exec);
        service = new ClientAccessService(executors, directory, store, new ClientAccessProperties(true), sso(), audit);
        when(directory.requireClient(any(), anyString())).thenAnswer(i -> client(i.getArgument(1)));
    }

    @Test
    void exemptClientIsGrantedWithoutReadingAnything() {
        var check = service.checkLogin("anyone", "sso-admin-ui");
        assertThat(check.hasAccess()).isTrue();
        assertThat(check.exempt()).isTrue();
        verify(store, never()).read(any(), any());
    }

    @Test
    void noPolicyFollowsDefaultDeny() {
        when(directory.findUserByUsername(realm, "u")).thenReturn(Optional.of(user("uid", "u")));
        when(directory.userGroups(realm, "uid")).thenReturn(List.of());
        when(store.read(realm, "uuid-app")).thenReturn(List.of());
        var check = service.checkLogin("u", "app");
        assertThat(check.hasAccess()).isFalse();
        assertThat(check.reason()).isEqualTo(AccessReason.NO_POLICY);
        assertThat(check.message()).contains("default-deny");

        ClientAccessService open = new ClientAccessService(executors, directory, store, new ClientAccessProperties(false),
                sso(), audit);
        assertThat(open.checkLogin("u", "app").hasAccess()).isTrue();
    }

    @Test
    void denyBeatsGroupAllowAndRequiredGroupsAreNamed() {
        when(directory.findUserByUsername(realm, "u")).thenReturn(Optional.of(user("uid", "u")));
        when(directory.userGroups(realm, "uid")).thenReturn(List.of(group("g1", "minagri")));
        when(directory.getGroup(realm, "g1")).thenReturn(Optional.of(group("g1", "minagri")));
        when(store.read(realm, "uuid-app")).thenReturn(List.of(
                AccessPolicy.group("p1", CanonicalPolicies.GROUPS, false, List.of("g1")),
                AccessPolicy.user("p2", CanonicalPolicies.DENIED_USERS, true, List.of("uid"))));
        var check = service.checkLogin("u", "app");
        assertThat(check.hasAccess()).isFalse();
        assertThat(check.reason()).isEqualTo(AccessReason.DENIED);
        assertThat(check.requiredGroups()).containsExactly("minagri");
        assertThat(check.userGroups()).containsExactly("minagri");
    }

    @Test
    void malformedPolicyIsSkippedNotFatal() {
        when(directory.findUserByUsername(realm, "u")).thenReturn(Optional.of(user("uid", "u")));
        when(directory.userGroups(realm, "uid")).thenReturn(List.of());
        when(store.read(realm, "uuid-app")).thenReturn(List.of(
                new AccessPolicy("bad", "x", "user", "POSITIVE", Map.of("users", "{not json")),
                AccessPolicy.user("p2", CanonicalPolicies.USERS, false, List.of("uid"))));
        assertThat(service.checkLogin("u", "app").hasAccess()).isTrue();
    }

    @Test
    void unknownUserIsARefusalNotAnError() {
        when(directory.findUserByUsername(realm, "ghost")).thenReturn(Optional.empty());
        var check = service.checkLogin("ghost", "app");
        assertThat(check.hasAccess()).isFalse();
        assertThat(check.message()).contains("not found");
    }

    @Test
    void serviceAccountWithoutPermissionIs503() {
        when(directory.findUserByUsername(realm, "u")).thenThrow(new ForbiddenException());
        assertThatThrownBy(() -> service.checkLogin("u", "app")).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void enforceLoginAuditsAndThrowsAccessDenied() {
        when(directory.findUserByUsername(realm, "u")).thenReturn(Optional.of(user("uid", "u")));
        when(directory.userGroups(realm, "uid")).thenReturn(List.of());
        when(store.read(realm, "uuid-app")).thenReturn(List.of());
        assertThatThrownBy(() -> service.enforceLogin("u", "app", "1.2.3.4", "refresh_token"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).error()).isEqualTo("access_denied"));
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.LOGIN_DENIED
                && "u".equals(e.actor()) && "refresh_token".equals(e.extra().get("grant_type"))));
    }

    @Test
    void admitUserWritesOneUserPolicyAndLiftsTheDenial() {
        when(store.read(realm, "uuid-app")).thenReturn(List.of(
                AccessPolicy.user("p2", CanonicalPolicies.DENIED_USERS, true, List.of("uid"))));
        var r = service.admitUser(exec, "app", "uid");
        assertThat(r.admitted()).isTrue();
        assertThat(r.liftedDenial()).isTrue();
        verify(store).apply(eq(realm), eq("uuid-app"), argThat(changes -> changes.size() == 2
                && changes.get(0).op() == CanonicalPolicies.Op.CREATE
                && changes.get(0).body().userIds().equals(List.of("uid"))
                && changes.get(1).op() == CanonicalPolicies.Op.DELETE));
    }

    @Test
    void revokeViaGroupAddsADenyEntryInOnePass() {
        when(store.read(realm, "uuid-app")).thenReturn(List.of(
                AccessPolicy.group("p1", CanonicalPolicies.GROUPS, false, List.of("g1"))));
        var r = service.revokeUsers(exec, "app", List.of(new RevokeTarget("uid", List.of("g1")),
                new RevokeTarget("other", List.of())));
        assertThat(r.denied()).containsExactly("uid");
        assertThat(r.notAssigned()).containsExactly("other");
        verify(store).read(realm, "uuid-app");
        verify(store).apply(eq(realm), eq("uuid-app"), argThat(changes -> changes.size() == 1
                && changes.get(0).body().name().equals(CanonicalPolicies.DENIED_USERS)));
    }

    @Test
    void ungrantGroupRemovesOnlyThatGroup() {
        when(store.read(realm, "uuid-app")).thenReturn(List.of(
                AccessPolicy.group("p1", CanonicalPolicies.GROUPS, false, List.of("g1", "g2"))));
        assertThat(service.ungrantGroup(exec, "app", "g1").groupIds()).containsExactly("g2");
    }

    @Test
    void execForCrossCuttingIsTheServiceAccount() {
        KeycloakExec caller = KeycloakExec.of(realm, true);
        assertThat(service.execFor(caller, true)).isSameAs(exec);
        assertThat(service.execFor(caller, false)).isSameAs(caller);
        assertThat(service.execFor(exec, true)).isSameAs(exec);
    }

    @Test
    void removeNamedGrantsEverywhereSkipsExemptAndReportsFailures() {
        when(directory.listClients(realm)).thenReturn(List.of(client("sso-admin-ui"), client("account"), client("a"),
                client("b"), client("c")));
        when(store.read(realm, "uuid-a")).thenReturn(List.of(AccessPolicy.user("p", CanonicalPolicies.USERS, false,
                List.of("uid", "keep"))));
        when(store.read(realm, "uuid-b")).thenReturn(List.of());
        when(store.read(realm, "uuid-c")).thenThrow(new InternalServerErrorException("boom"));
        var r = service.removeNamedGrantsEverywhere("uid");
        assertThat(r.changedClientIds()).containsExactly("a");
        assertThat(r.failures()).containsKey("c");
        verify(store, never()).read(realm, "uuid-sso-admin-ui");
        verify(store).apply(eq(realm), eq("uuid-a"), argThat(changes -> changes.size() == 1
                && changes.get(0).body().userIds().equals(List.of("keep"))));
    }

    @Test
    void userViewReportsUnreadableClientsAsUnknown() {
        when(directory.listClients(realm)).thenReturn(List.of(client("a"), client("b"), client("sso-admin-ui"),
                client("account")));
        when(directory.getUser(realm, "uid")).thenReturn(Optional.of(user("uid", "u")));
        when(directory.userGroups(realm, "uid")).thenReturn(List.of(group("g1", "minagri")));
        when(store.read(realm, "uuid-a")).thenReturn(List.of(AccessPolicy.group("p", CanonicalPolicies.GROUPS, false,
                List.of("g1"))));
        doThrow(new ForbiddenException()).when(store).read(realm, "uuid-b");
        var view = service.userClientAccess(exec, "uid");
        assertThat(view.items()).extracting(ClientAccessViews.AccessItem::clientId)
                .containsExactly("a", "b", "sso-admin-ui"); // built-ins left out, exempt last
        assertThat(view.items().get(0).state()).isEqualTo("granted");
        assertThat(view.items().get(0).viaGroups().get(0).name()).isEqualTo("minagri");
        assertThat(view.items().get(1).state()).isEqualTo("unknown");
        assertThat(view.items().get(1).error()).contains("403");
        assertThat(view.items().get(2).state()).isEqualTo("exempt");
    }

    @Test
    void institutionWithdrawalLeavesOtherInstitutionsAlone() {
        when(store.read(realm, "uuid-app")).thenReturn(List.of(
                AccessPolicy.group("p1", CanonicalPolicies.GROUPS, false, List.of("g1", "g2")),
                AccessPolicy.user("p2", CanonicalPolicies.USERS, false, List.of("u1", "u2"))));
        when(directory.getGroup(realm, "g1")).thenReturn(Optional.of(group("g1", "MINAGRI")));
        when(directory.getGroup(realm, "g2")).thenReturn(Optional.of(group("g2", "minisante")));
        UserRepresentation u1 = user("u1", "a");
        u1.setAttributes(Map.of("institution", List.of("minagri")));
        UserRepresentation u2 = user("u2", "b");
        u2.setAttributes(Map.of("institution", List.of("minisante")));
        when(directory.getUser(realm, "u1")).thenReturn(Optional.of(u1));
        when(directory.getUser(realm, "u2")).thenReturn(Optional.of(u2));
        var r = service.removeInstitutionAccess(exec, "app", "minagri", null);
        assertThat(r.removedUserIds()).containsExactly("u1");
        assertThat(r.assignment().groupIds()).containsExactly("g2");
        assertThat(r.assignment().userIds()).containsExactly("u2");

        var summary = service.institutionAccessSummary(exec, "app", List.of("minagri", "minisante", "rdb"));
        assertThat(summary.get("rdb").users()).isZero();
        assertThat(summary.get("minagri").groupAssigned()).isTrue();
    }

    @Test
    void decideForUsersReadsPoliciesOnce() {
        when(store.read(realm, "uuid-app")).thenReturn(List.of(AccessPolicy.user("p", CanonicalPolicies.USERS, false,
                List.of("a"))));
        var d = service.decideForUsers(exec, "app", Map.of("a", List.of(), "b", List.of()));
        assertThat(d.get("a").hasAccess()).isTrue();
        assertThat(d.get("b").hasAccess()).isFalse();
        verify(store).read(realm, "uuid-app");
    }
}
