package rw.gov.risa.sso.wrapper.service.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.http.HttpStatus;

import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessDecision;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessReason;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.mail.DeliveryResult;
import rw.gov.risa.sso.wrapper.service.mail.MailSettings;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;
import rw.gov.risa.sso.wrapper.service.provisioning.AccessChangeNotifier;

class AuthorizationAdminServiceTest {

    final PlatformMocks m = new PlatformMocks();
    final ClientAccessService access = mock(ClientAccessService.class);
    final Mailer mailer = mock(Mailer.class);
    final AccessChangeNotifier notifier = new AccessChangeNotifier(mailer);
    final AuthorizationAdminService service = new AuthorizationAdminService(m.support, access, notifier);

    final ClientRepresentation app = PlatformMocks.client("minagri-app", Map.of());
    final UserRepresentation jane = new UserRepresentation();

    static AccessDecision notGranted() {
        return new AccessDecision(false, AccessReason.NOT_ASSIGNED, false, List.of(), false, false, List.of());
    }

    static AccessDecision grantedByName() {
        return new AccessDecision(true, AccessReason.ASSIGNED, true, List.of(), false, false, List.of());
    }

    static AccessDecision grantedViaGroup() {
        return new AccessDecision(true, AccessReason.ASSIGNED, false, List.of("g1"), false, false, List.of());
    }

    static AccessDecision open() {
        return new AccessDecision(true, AccessReason.DENY_LIST_ONLY, false, List.of(), false, false, List.of());
    }

    static AccessDecision revokedByName() {
        return new AccessDecision(false, AccessReason.DENIED, true, List.of(), true, true, List.of());
    }

    static AccessDecision deniedByGroup() {
        return new AccessDecision(false, AccessReason.DENIED, false, List.of(), true, false, List.of("g9"));
    }

    @BeforeEach
    void setUp() {
        jane.setId("u1");
        jane.setUsername("jane");
        jane.setEmail("jane@gov.rw");
        jane.setAttributes(Map.of("institution", List.of("minagri")));
        when(m.directory.getUser(m.realm, "u1")).thenReturn(Optional.of(jane));
        when(access.client("minagri-app")).thenReturn(app);
        GroupRepresentation g = new GroupRepresentation();
        g.setId("g1");
        g.setName("minagri");
        when(m.directory.userGroups(m.realm, "u1")).thenReturn(List.of(g));
        when(mailer.settings()).thenReturn(new MailSettings("from@gov.rw", "http://login", true));
        when(mailer.configured()).thenReturn(true);
        when(mailer.sendAccessGranted(any())).thenReturn(DeliveryResult.sent());
        when(mailer.sendAccessRevoked(any())).thenReturn(DeliveryResult.sent());
    }

    @SuppressWarnings("unchecked")
    void decisions(AccessDecision before, AccessDecision after) {
        when(access.decide(any(KeycloakExec.class), eq("u1"), any(Collection.class), eq(app))).thenReturn(before, after);
    }

    AuditEvent onlyAudit() {
        ArgumentCaptor<AuditEvent.Builder> a = ArgumentCaptor.forClass(AuditEvent.Builder.class);
        verify(m.audit).emit(a.capture());
        return a.getValue().build();
    }

    @Test
    void staleExpectedStateIs409StateChangedAndWritesNothing() {
        decisions(grantedByName(), grantedByName());
        assertThatThrownBy(() -> service.changeUserAccess("u1", "minagri-app", "grant", "not_granted", PlatformMocks.SUPER))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.error()).isEqualTo("state_changed");
                    assertThat(e.details()).containsEntry("current_state", "granted");
                });
        verify(access, never()).admitUser(any(), any(), any());
        verifyNoInteractions(m.audit, mailer);
    }

    @Test
    void grantAdmitsByNameAuditsPerUserClientAndEmails() {
        decisions(notGranted(), grantedByName());
        when(access.admitUser(any(), eq("minagri-app"), eq("u1")))
                .thenReturn(new ClientAccessWrites.AdmitResult(null, true, false));
        Map<String, Object> out = service.changeUserAccess("u1", "minagri-app", "grant", "not_granted", PlatformMocks.ADMIN);
        assertThat(out).containsEntry("previous_state", "not_granted").containsEntry("notified", "sent")
                .containsEntry("lifted_deny", false);
        AuditEvent e = onlyAudit();
        assertThat(e.type()).isEqualTo(AuditEventType.ACCESS_GRANTED);
        assertThat(e.actor()).isEqualTo("platform.admin");
        assertThat(e.clientId()).isEqualTo("minagri-app");
        assertThat(e.extra()).containsEntry("target_user", "u1").containsEntry("institution", "minagri")
                .containsEntry("previous_state", "not_granted").containsEntry("new_state", "granted");
        verify(mailer).sendAccessGranted(org.mockito.ArgumentMatchers.argThat(mail -> "jane@gov.rw".equals(mail.to())
                && "minagri".equals(mail.institution()) && mail.applications().equals(List.of("minagri-app"))));
    }

    @Test
    void revokeOfAGroupHolderUnassignsAndDenies() {
        decisions(grantedViaGroup(), revokedByName());
        when(access.revokeUsers(any(), eq("minagri-app"), anyList())).thenReturn(new ClientAccessWrites.RevokeResult(
                null, List.of(), List.of("u1"), List.of(), List.of()));
        Map<String, Object> out = service.changeUserAccess("u1", "minagri-app", "revoke", "granted", PlatformMocks.SUPER);
        assertThat(out).containsEntry("denied", true).containsEntry("unassigned", false);
        assertThat(onlyAudit().type()).isEqualTo(AuditEventType.ACCESS_REVOKED);
        verify(mailer).sendAccessRevoked(any());
    }

    @Test
    void revokeOfAnOpenClientWritesADenyEntry() {
        decisions(open(), revokedByName());
        when(access.denyUsers(any(), eq("minagri-app"), anyList()))
                .thenReturn(new ClientAccessWrites.DenyResult(null, List.of("u1")));
        Map<String, Object> out = service.changeUserAccess("u1", "minagri-app", "revoke", "open", PlatformMocks.SUPER);
        assertThat(out).containsEntry("denied", true);
        verify(access, never()).revokeUsers(any(), any(), anyList());
    }

    @Test
    void restoreLiftsTheDenial() {
        decisions(revokedByName(), grantedByName());
        when(access.admitUser(any(), eq("minagri-app"), eq("u1")))
                .thenReturn(new ClientAccessWrites.AdmitResult(null, true, true));
        Map<String, Object> out = service.changeUserAccess("u1", "minagri-app", "restore", "revoked", PlatformMocks.SUPER);
        assertThat(out).containsEntry("lifted_deny", true);
        assertThat(onlyAudit().extra()).containsEntry("requested_action", "restore");
    }

    @Test
    void restoreOnANotGrantedCellIsNotApplicable() {
        decisions(notGranted(), notGranted());
        assertThatThrownBy(() -> service.changeUserAccess("u1", "minagri-app", "restore", "not_granted",
                PlatformMocks.SUPER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("not_applicable"));
    }

    @Test
    void grantWhileDeniedByAGroupIsRefused() {
        decisions(deniedByGroup(), deniedByGroup());
        assertThatThrownBy(() -> service.changeUserAccess("u1", "minagri-app", "grant", "revoked", PlatformMocks.SUPER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("denied_by_group"));
    }

    @Test
    void noEmailWhenTheGatesAnswerDidNotChange() {
        decisions(notGranted(), notGranted());
        when(access.admitUser(any(), any(), any())).thenReturn(new ClientAccessWrites.AdmitResult(null, false, false));
        Map<String, Object> out = service.changeUserAccess("u1", "minagri-app", "grant", "not_granted", PlatformMocks.SUPER);
        assertThat(out.get("notified")).isNull();
        verify(mailer, never()).sendAccessGranted(any());
    }

    @Test
    void assigningAnInstitutionGroupOutsideTheClientsScopeIs400() {
        ClientRepresentation owned = PlatformMocks.client("minagri-app", Map.of("sso.client.owner_institution", "minagri"));
        when(access.client("minagri-app")).thenReturn(owned);
        GroupRepresentation other = new GroupRepresentation();
        other.setId("g2");
        other.setName("MINISANTE");
        when(m.directory.getGroup(m.realm, "g2")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.setClientAccess("minagri-app", List.of(), List.of("g2"), PlatformMocks.SUPER))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("minisante");
                });
        verify(access, never()).setAssignments(any(), any(), anyList(), anyList());
    }

    @Test
    void clientAccessListsDeniedUsersByName() {
        when(access.describeAssignment(any(), eq("minagri-app"))).thenReturn(new ClientAccessViews.AssignmentView(
                "minagri-app", true, List.of(new ClientAccessViews.NamedRef("u2", "bob")),
                List.of(new ClientAccessViews.NamedRef("g1", "minagri")), List.of("u1")));
        Map<String, Object> out = service.clientAccess("minagri-app");
        assertThat(out).containsEntry("enforced", true);
        assertThat(out.get("users")).asList().first().asString().contains("username=bob");
        assertThat(out.get("denied_users")).asList().first().asString().contains("username=jane");
    }

    @Test
    void setClientAccessAuditsTheDifference() {
        ClientAccessViews.AssignmentView before = new ClientAccessViews.AssignmentView("minagri-app", true,
                List.of(new ClientAccessViews.NamedRef("u2", "bob")), List.of(), List.of());
        ClientAccessViews.AssignmentView after = new ClientAccessViews.AssignmentView("minagri-app", true,
                List.of(new ClientAccessViews.NamedRef("u1", "jane")), List.of(), List.of());
        when(access.describeAssignment(any(), eq("minagri-app"))).thenReturn(before);
        when(access.setAssignments(any(), eq("minagri-app"), eq(List.of("u1")), eq(List.of()))).thenReturn(after);
        service.setClientAccess("minagri-app", List.of("u1"), List.of(), PlatformMocks.SUPER);
        AuditEvent e = onlyAudit();
        assertThat(e.type()).isEqualTo(AuditEventType.ACCESS_POLICY_UPDATED);
        assertThat(e.extra()).containsEntry("users_added", List.of("u1")).containsEntry("users_removed", List.of("u2"));
    }
}
