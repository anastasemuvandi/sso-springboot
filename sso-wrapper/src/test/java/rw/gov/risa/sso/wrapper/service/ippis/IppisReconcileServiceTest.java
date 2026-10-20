package rw.gov.risa.sso.wrapper.service.ippis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures.at;
import static rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures.closed;
import static rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures.open;
import static rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures.person;
import static rw.gov.risa.sso.wrapper.service.ippis.IppisFixtures.user;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.ArgumentCaptor;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisOutcome;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPerson;
import rw.gov.risa.sso.wrapper.service.ippis.IppisReconcileService.RunOptions;
import rw.gov.risa.sso.wrapper.service.lifecycle.KeycloakUserGateway;
import rw.gov.risa.sso.wrapper.service.lifecycle.LeaverAccessStripper;
import rw.gov.risa.sso.wrapper.service.lifecycle.LifecycleService;
import rw.gov.risa.sso.wrapper.service.lifecycle.UserOps;

class IppisReconcileServiceTest {

    IppisClient ippis = mock(IppisClient.class);
    KeycloakUserGateway gateway = mock(KeycloakUserGateway.class);
    UserOps ops = mock(UserOps.class);
    LeaverAccessStripper stripper = mock(LeaverAccessStripper.class);
    AuditEmitter audit = mock(AuditEmitter.class);
    IdentityMirrorWriter mirror = mock(IdentityMirrorWriter.class);
    WatermarkStore watermark = mock(WatermarkStore.class);
    IppisRegistryProvider registry = mock(IppisRegistryProvider.class);
    IppisReconcileService service;

    @BeforeEach
    void setUp() {
        when(gateway.open()).thenReturn(ops);
        when(registry.registry()).thenReturn(IppisFixtures.registry());
        when(mirror.recorder()).thenReturn(Optional.empty());
        when(ops.updateAttributes(any(), anyMap())).thenReturn(true);
        LifecycleService lifecycle = new LifecycleService(gateway, stripper, audit);
        service = new IppisReconcileService(ippis, gateway, lifecycle, registry, mirror, watermark, audit,
                IppisFixtures.props(""));
    }

    private void fullPassOver(UserRepresentation... users) {
        when(ops.listUsers(anyInt(), anyInt())).thenReturn(List.of(users));
    }

    @Test
    void userWithoutEmployeeNumberIsSkippedAndNeverLookedUp() {
        fullPassOver(user("u1", null, true, Map.of()));
        RunReport r = service.runFull(RunOptions.defaults());
        assertThat(r.summary().count(IppisOutcome.SKIPPED)).isEqualTo(1);
        verify(ippis, never()).getPerson(any());
    }

    @Test
    void employeeIppisDoesNotKnowIsNotFound() {
        fullPassOver(user("u1", "EMP1", true, Map.of()));
        when(ippis.getPerson("EMP1")).thenReturn(Optional.empty());
        RunReport r = service.runFull(RunOptions.defaults());
        assertThat(r.summary().count(IppisOutcome.NOT_FOUND)).isEqualTo(1);
        verify(ops, never()).updateAttributes(any(), anyMap());
    }

    @Test
    void writesTheRegistrySlugNeverTheRawCodeAndNeverTheEmail() {
        fullPassOver(user("u1", "EMP1", true, Map.of("institution", "MINAGRI")));
        when(ippis.getPerson("EMP1")).thenReturn(Optional.of(person("EMP1", "NID1",
                at("MINAGRI", open("j1", "2020-01-01", "Agronomist")))));
        RunReport r = service.runFull(RunOptions.defaults());

        assertThat(r.summary().count(IppisOutcome.UPDATED)).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> write = ArgumentCaptor.forClass(Map.class);
        verify(ops).updateAttributes(eq("u1"), write.capture());
        assertThat(write.getValue()).containsEntry("institution", "minagri") // legacy UPPERCASE normalised
                .containsEntry("position", "Agronomist").containsEntry("national_id", "NID1")
                .containsEntry("ippis_status", "active").doesNotContainKey("email");
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.SYNC_ATTRIBUTES
                && "u1".equals(e.extra().get("target_user")) && "minagri".equals(e.extra().get("institution"))));
        verify(watermark).write(any(Instant.class));
    }

    @Test
    void unchangedUserIsNotWrittenNorAudited() {
        fullPassOver(user("u1", "EMP1", true, Map.of("institution", "minagri", "position", "Agronomist",
                "national_id", "NID1", "phone", "07880001", "ippis_status", "active")));
        when(ippis.getPerson("EMP1")).thenReturn(Optional.of(person("EMP1", "NID1",
                at("MINAGRI", open("j1", "2020-01-01", "Agronomist")))));
        RunReport r = service.runFull(RunOptions.defaults());
        assertThat(r.summary().count(IppisOutcome.UNCHANGED)).isEqualTo(1);
        verify(ops, never()).updateAttributes(any(), anyMap());
        verify(audit, never()).emit(any(AuditEvent.class));
    }

    @Test
    void unmappedCodeIsNeverWrittenAndAuditedOncePerRun() {
        fullPassOver(user("u1", "EMP1", true, Map.of("institution", "minagri")),
                user("u2", "EMP2", true, Map.of()));
        when(ippis.getPerson("EMP1")).thenReturn(Optional.of(person("EMP1", "N1", at("MOH", open("a", "2020-01-01", "X")))));
        when(ippis.getPerson("EMP2")).thenReturn(Optional.of(person("EMP2", "N2", at("MOH", open("b", "2020-01-01", "Y")))));
        RunReport r = service.runFull(RunOptions.defaults());

        assertThat(r.summary().unmappedInstitution()).isEqualTo(2);
        assertThat(r.unmappedCodes()).containsExactly("MOH");
        verify(ops, times(2)).updateAttributes(any(), argThat(m -> !m.containsKey("institution")));
        verify(audit, times(1)).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.SYNC_UNMAPPED_INSTITUTION
                && e.extra().get("codes").equals(List.of("MOH"))));
    }

    @Test
    void ambiguousPrimaryLeavesInstitutionUnwrittenAndIsCounted() {
        fullPassOver(user("u1", "EMP1", true, Map.of("institution", "minagri")));
        when(ippis.getPerson("EMP1")).thenReturn(Optional.of(person("EMP1", "N1",
                at("MINAGRI", open("a", "2024-03-01", "P")), at("MINEDUC", open("b", "2024-03-01", "P")))));
        RunReport r = service.runFull(RunOptions.defaults());
        assertThat(r.summary().ambiguousInstitution()).isEqualTo(1);
        verify(ops).updateAttributes(eq("u1"), argThat(m -> !m.containsKey("institution")));
    }

    @Test
    void deltaRecordWithNoAccountIsNoUserAndNeverCreatesOne() {
        when(watermark.read()).thenReturn(Optional.empty());
        IppisPerson p = person("EMP9", "N9", at("MINAGRI", open("a", "2024-03-01", "P")));
        when(ippis.listPeopleChangedSince(null)).thenReturn(List.of(p));
        when(ops.findByAttribute("employee_number", "EMP9")).thenReturn(List.of());
        RunReport r = service.runDelta(RunOptions.defaults());
        assertThat(r.summary().count(IppisOutcome.NO_USER)).isEqualTo(1);
        verify(ops, never()).updateAttributes(any(), anyMap());
        verify(watermark).write(Instant.parse("2026-09-01T08:00:00Z"));
    }

    @Test
    void dryRunWritesNothingAndKeepsTheWatermark() {
        when(watermark.read()).thenReturn(Optional.empty());
        when(ippis.listPeopleChangedSince(null)).thenReturn(List.of(
                person("EMP1", "N1", at("MINAGRI", open("a", "2024-03-01", "P")))));
        when(ops.findByAttribute("employee_number", "EMP1")).thenReturn(List.of(user("u1", "EMP1", true, Map.of())));
        RunReport r = service.runDelta(new RunOptions(true, true, null, "test"));
        assertThat(r.summary().count(IppisOutcome.UPDATED)).isEqualTo(1);
        verify(ops, never()).updateAttributes(any(), anyMap());
        verify(watermark, never()).write(any());
    }

    @Test
    void leaverIsOffboardedRolesAndNamedGrantsStripped() {
        fullPassOver(user("u1", "EMP1", true, Map.of("institution", "minagri")));
        when(ippis.getPerson("EMP1")).thenReturn(Optional.of(person("EMP1", "N1",
                at("MINAGRI", closed("a", "2015-01-01", "2026-06-30", "Clerk")))));
        when(ops.getUser("u1")).thenReturn(Optional.of(user("u1", "EMP1", true, Map.of("institution", "minagri"))));
        RoleRepresentation inst = new RoleRepresentation("inst:minagri:admin", null, false);
        RoleRepresentation other = new RoleRepresentation("offline_access", null, false);
        when(ops.realmRoles("u1")).thenReturn(List.of(inst, other));
        when(ops.groups("u1")).thenReturn(List.of());
        when(stripper.removeNamedGrantsEverywhere("u1")).thenReturn(List.of("minagri-kubaka-web"));

        RunReport r = service.runFull(RunOptions.defaults());

        assertThat(r.summary().count(IppisOutcome.DISABLED)).isEqualTo(1);
        verify(ops).logout("u1");
        verify(ops).setEnabled(eq("u1"), eq(false), argThat(m -> m.get("offboarded_by").equals(List.of("ippis-reconcile"))));
        verify(ops).removeRealmRoles("u1", List.of(inst));
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.LIFECYCLE_OFFBOARD
                && e.extra().get("roles_removed").equals(List.of("inst:minagri:admin"))
                && e.extra().get("client_grants_removed").equals(List.of("minagri-kubaka-web"))));
        verify(ops, never()).updateAttributes(any(), anyMap());
    }

    @Test
    void resigningOneOfTwoPostsIsAMoveNotAnExit() {
        fullPassOver(user("u1", "EMP1", true, Map.of("institution", "minagri")));
        when(ippis.getPerson("EMP1")).thenReturn(Optional.of(person("EMP1", "N1",
                at("MINAGRI", closed("a", "2015-01-01", "2026-06-30", "Clerk")),
                at("MINISANTE", open("b", "2026-07-01", "Officer")))));
        RunReport r = service.runFull(RunOptions.defaults());
        assertThat(r.summary().count(IppisOutcome.UPDATED)).isEqualTo(1);
        verify(ops, never()).setEnabled(any(), anyBoolean(), anyMap());
        verify(ops).updateAttributes(eq("u1"), argThat(m -> "minisante".equals(m.get("institution"))));
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.SYNC_ATTRIBUTES
                && "minagri".equals(e.extra().get("institution_before"))));
    }

    @Test
    void alreadyDisabledLeaverIsIdempotent() {
        fullPassOver(user("u1", "EMP1", false, Map.of()));
        when(ippis.getPerson("EMP1")).thenReturn(Optional.of(person("EMP1", "N1",
                at("MINAGRI", closed("a", "2015-01-01", "2026-06-30", "Clerk")))));
        RunReport r = service.runFull(RunOptions.defaults());
        assertThat(r.summary().count(IppisOutcome.ALREADY_DISABLED)).isEqualTo(1);
        verify(ops, never()).logout(any());
    }

    @Test
    void feedOffboardedAccountIsReEnabledOnRehireAndAudited() {
        fullPassOver(user("u1", "EMP1", false, Map.of("offboarded_by", "ippis-reconcile",
                "offboarded_at", "2026-07-01T00:00:00Z")));
        when(ippis.getPerson("EMP1")).thenReturn(Optional.of(person("EMP1", "N1",
                at("MINAGRI", open("b", "2026-09-01", "Officer")))));
        RunReport r = service.runFull(RunOptions.defaults());
        assertThat(r.rehired()).containsExactly("u1");
        verify(ops).setEnabled(eq("u1"), eq(true), argThat(m -> m.containsKey("offboarded_by") && m.get("offboarded_by") == null));
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.LIFECYCLE_REHIRE));
    }

    @Test
    void manuallyDisabledAccountIsNeverReEnabledByTheFeed() {
        fullPassOver(user("u1", "EMP1", false, Map.of("offboarded_by", "platform.super")));
        when(ippis.getPerson("EMP1")).thenReturn(Optional.of(person("EMP1", "N1",
                at("MINAGRI", open("b", "2026-09-01", "Officer")))));
        RunReport r = service.runFull(RunOptions.defaults());
        assertThat(r.rehired()).isEmpty();
        verify(ops, never()).setEnabled(any(), eq(true), anyMap());
    }

    @Test
    void ippisFailureIsIsolatedPerUser() {
        fullPassOver(user("u1", "EMP1", true, Map.of()), user("u2", "EMP2", true, Map.of()));
        when(ippis.getPerson("EMP1")).thenThrow(new IllegalStateException("boom"));
        when(ippis.getPerson("EMP2")).thenReturn(Optional.of(person("EMP2", "N2", at("MINAGRI", open("a", "2020-01-01", "X")))));
        RunReport r = service.runFull(RunOptions.defaults());
        assertThat(r.summary().count(IppisOutcome.ERROR)).isEqualTo(1);
        assertThat(r.summary().count(IppisOutcome.UPDATED)).isEqualTo(1);
    }
}
