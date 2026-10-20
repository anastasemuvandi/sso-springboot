package rw.gov.risa.sso.wrapper.service.mfa;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;

import rw.gov.risa.sso.mfa.policy.MfaConstants;
import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;

class MfaServiceTest {

    RealmResource realm = mock(RealmResource.class, RETURNS_DEEP_STUBS);
    KeycloakExecutors executors = mock(KeycloakExecutors.class);
    KeycloakDirectory directory = mock(KeycloakDirectory.class);
    MfaBulkEnrolment bulk = mock(MfaBulkEnrolment.class);
    AuditEmitter audit = mock(AuditEmitter.class);
    RealmRepresentation rep = new RealmRepresentation();
    MfaService service;

    @BeforeEach
    void setUp() {
        when(executors.serviceAccount()).thenReturn(KeycloakExec.of(realm));
        when(realm.toRepresentation()).thenReturn(rep);
        rep.setBrowserFlow(MfaConstants.MFA_FLOW_ALIAS);
        rep.setAttributes(new HashMap<>(Map.of(MfaConstants.MODE_ATTRIBUTE, "off")));
        service = new MfaService(executors, directory, bulk, audit, Clock.systemUTC());
    }

    @Test
    void unknownModeIs400AndFlowNotReadyIs409() {
        assertThatThrownBy(() -> service.setMode("maybe", "root", null))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        rep.setBrowserFlow("browser");
        assertThatThrownBy(() -> service.setMode("all", "root", null))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
        // Turning OFF never needs the flow.
        assertThat(service.setMode("off", "root", null).changed()).isFalse();
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.MFA_SWITCH_CHANGED
                && "off".equals(e.extra().get("old_value")) && Boolean.FALSE.equals(e.extra().get("changed"))));
    }

    @Test
    void firstTimeAllStartsBulkEnrolmentOnlyOnce() {
        when(bulk.startAsync("root")).thenReturn(Map.of("started", true));
        var first = service.setMode("all", "root", null);
        assertThat(first.bulkEnrol()).containsEntry("started", true);
        assertThat(rep.getAttributes()).containsEntry(MfaConstants.MODE_ATTRIBUTE, "all")
                .containsKey(MfaConstants.FIRST_ENABLED_ATTRIBUTE);

        service.setMode("per_client", "root", null);
        var again = service.setMode("all", "root", null);
        assertThat(again.bulkEnrol()).isNull();
        verify(bulk).startAsync("root");
    }

    @Test
    void perClientModeDoesNotStartBulk() {
        service.setMode("per_client", "root", null);
        verify(bulk, never()).startAsync(anyString());
    }

    @Test
    void clientRequirementWritesOnlyWhenChangedAndAuditsOldAndNew() {
        ClientRepresentation c = new ClientRepresentation();
        c.setClientId("app");
        c.setId("uuid");
        c.setAttributes(new HashMap<>(Map.of("sso.client.owner_institution", "minagri")));
        when(directory.requireClient(any(), eq("app"))).thenReturn(c);
        var r = service.setClientRequirement("app", true, "root", null, null);
        assertThat(r.previous()).isTrue(); // unset = required
        assertThat(r.changed()).isTrue();  // "true" is now written explicitly
        assertThat(c.getAttributes()).containsEntry(MfaConstants.CLIENT_ATTRIBUTE, "true");
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.MFA_CLIENT_REQUIREMENT_CHANGED
                && "minagri".equals(e.extra().get("institution"))));
        assertThat(service.setClientRequirement("app", true, "root", null, null).changed()).isFalse();
    }

    @Test
    void delegatedAdminsMayOnlyTurnItOn() {
        MfaService.refuseDelegatedChange(true);
        assertThatThrownBy(() -> MfaService.refuseDelegatedChange(false))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));
    }

    @Test
    void exemptionNeedsAReasonAndTheRole() {
        assertThatThrownBy(() -> service.grantExemption("u", "short", "root", null))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        when(directory.realmRole(any(), eq(MfaConstants.EXEMPT_ROLE))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.grantExemption("u", "A long enough justification text", "root", null))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void exemptRoleIsRefusedOnGenericPaths() {
        MfaService.refuseExemptRole(List.of("inst:minagri:staff"), "x");
        assertThatThrownBy(() -> MfaService.refuseExemptRole(List.of("mfa-exempt"), "PUT /users/{id}/roles"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void passwordGrantGate() {
        rep.getAttributes().put(MfaConstants.MODE_ATTRIBUTE, "all");
        when(directory.findClient(any(), eq("app"))).thenReturn(Optional.empty());
        assertThat(service.passwordGrantRefusal(Map.of("acr", "1"), "app")).isPresent();
        assertThat(service.passwordGrantRefusal(Map.of("acr", "2"), "app")).isEmpty();
        assertThatThrownBy(() -> service.enforcePasswordGrant(Map.of("acr", "1"), "u", "app", null, "/auth/login"))
                .satisfies(e -> assertThat(((ApiException) e).error()).isEqualTo("mfa_required"));
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.MFA_PASSWORD_GRANT_REFUSED));
        assertThat(service.totpRequiredFor("app", List.of("mfa-exempt"))).isFalse();
    }

    @Test
    void passwordGrantFailsOpenWhenTheSwitchIsUnreadable() {
        when(realm.toRepresentation()).thenThrow(new jakarta.ws.rs.ProcessingException("down"));
        assertThat(service.passwordGrantRefusal(Map.of("acr", "1"), "app")).isEmpty();
    }

    @Test
    void stateIsCachedBriefly() {
        service.currentState();
        service.currentState();
        verify(realm, org.mockito.Mockito.times(1)).toRepresentation();
        service.invalidate();
        service.currentState();
        verify(realm, org.mockito.Mockito.times(2)).toRepresentation();
    }
}
