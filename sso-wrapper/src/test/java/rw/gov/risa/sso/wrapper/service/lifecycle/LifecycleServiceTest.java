package rw.gov.risa.sso.wrapper.service.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures;

class LifecycleServiceTest {

    KeycloakUserGateway gateway = mock(KeycloakUserGateway.class);
    UserOps ops = mock(UserOps.class);
    LeaverAccessStripper stripper = mock(LeaverAccessStripper.class);
    AuditEmitter audit = mock(AuditEmitter.class);
    LifecycleService service = new LifecycleService(gateway, stripper, audit);
    RoleRepresentation inst = new RoleRepresentation("inst:minagri:hr", null, false);

    @BeforeEach
    void setUp() {
        when(gateway.open()).thenReturn(ops);
        when(ops.getUser("u1")).thenReturn(Optional.of(IppisFixtures.user("u1", "EMP1", true,
                Map.of("institution", "minagri"))));
        GroupRepresentation g = new GroupRepresentation();
        g.setId("g1");
        g.setName("minagri");
        when(ops.groups("u1")).thenReturn(List.of(g));
        when(ops.realmRoles("u1")).thenReturn(List.of(inst, new RoleRepresentation("default-roles", null, false)));
        when(ops.sessionCount("u1")).thenReturn(2);
        when(stripper.removeNamedGrantsEverywhere("u1")).thenReturn(List.of("minagri-app"));
    }

    @Test
    void terminateRemovesEverythingAndAuditsWhatWasRemovedWithTheTokenActor() {
        OffboardingResult r = service.terminate("u1", "left", "platform.super");
        assertThat(r.success()).isTrue();
        assertThat(r.sessionsKilled()).isEqualTo(2);
        assertThat(r.rolesRemoved()).containsExactly("inst:minagri:hr");
        assertThat(r.clientGrantsRemoved()).containsExactly("minagri-app");
        assertThat(r.groupsRemoved()).extracting(OffboardingResult.GroupSnapshot::name).containsExactly("minagri");
        verify(ops).logout("u1");
        verify(ops).removeFromGroup("u1", "g1");
        verify(ops).removeRealmRoles("u1", List.of(inst));
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.LIFECYCLE_OFFBOARD
                && "platform.super".equals(e.actor()) && "success".equals(e.outcome())
                && e.extra().get("groups_removed").equals(List.of("minagri"))));
    }

    @Test
    void failureRollsBackEnableGroupsAndRoles() {
        doThrow(new IllegalStateException("kc down")).when(stripper).removeNamedGrantsEverywhere("u1");
        assertThatThrownBy(() -> service.terminate("u1", "left", "admin"))
                .isInstanceOf(ApiException.class).extracting("error").isEqualTo("lifecycle_failed");
        verify(ops).setEnabled("u1", true, Map.of());
        verify(ops).addToGroup("u1", "g1");
        verify(ops).addRealmRoles("u1", List.of(inst));
        verify(audit).emit(argThat((AuditEvent e) -> "failure".equals(e.outcome())
                && Boolean.TRUE.equals(e.extra().get("rolled_back"))));
    }

    @Test
    void unknownUserIs404() {
        when(ops.getUser("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.terminate("nope", "x", "admin")).isInstanceOf(ApiException.class)
                .extracting("error").isEqualTo("not_found");
    }

    @Test
    void reassignRefusesADisabledAccount() {
        when(ops.getUser("u2")).thenReturn(Optional.of(IppisFixtures.user("u2", null, false, Map.of())));
        assertThatThrownBy(() -> service.reassign("u2", List.of(), List.of("x"), false, "r", "admin"))
                .isInstanceOf(ApiException.class).extracting("error").isEqualTo("account_disabled");
        verify(ops, never()).addToGroup(any(), any());
    }

    @Test
    void reassignSwapsGroupsAndRecordsUnknownNames() {
        GroupRepresentation a = new GroupRepresentation();
        a.setId("ga");
        a.setName("old");
        GroupRepresentation b = new GroupRepresentation();
        b.setId("gb");
        b.setName("new");
        when(ops.findGroupByName("old")).thenReturn(Optional.of(a));
        when(ops.findGroupByName("new")).thenReturn(Optional.of(b));
        when(ops.findGroupByName("ghost")).thenReturn(Optional.empty());
        ReassignmentResult r = service.reassign("u1", List.of("old"), List.of("new", "ghost"), true, "move", "admin");
        assertThat(r.groupsRemoved()).containsExactly("old");
        assertThat(r.groupsAdded()).containsExactly("new");
        assertThat(r.groupsNotFound()).containsExactly("ghost");
        assertThat(r.sessionRefreshed()).isTrue();
        verify(ops).logout("u1");
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.LIFECYCLE_REASSIGN
                && "admin".equals(e.actor())));
    }

    @Test
    void rehireIsDryRunSafe() {
        var view = rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPersonViews.viewOf(
                IppisFixtures.person("EMP1", "N", IppisFixtures.at("MINAGRI", IppisFixtures.open("a", "2026-01-01", "X"))));
        var user = IppisFixtures.user("u1", "EMP1", false, Map.of("offboarded_by", "ippis-leaver-feed"));
        assertThat(service.rehireIfEligible(ops, user, view, "t", true).wire()).isEqualTo("reenable");
        verify(ops, never()).setEnabled(eq("u1"), eq(true), anyMap());
    }
}
