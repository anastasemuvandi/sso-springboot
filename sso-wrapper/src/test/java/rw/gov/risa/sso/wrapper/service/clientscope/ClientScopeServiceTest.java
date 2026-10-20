package rw.gov.risa.sso.wrapper.service.clientscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionRegistryHolder;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

class ClientScopeServiceTest {

    RealmResource realm = mock(RealmResource.class, RETURNS_DEEP_STUBS);
    KeycloakExec exec = KeycloakExec.of(realm);
    KeycloakExecutors executors = mock(KeycloakExecutors.class);
    KeycloakDirectory directory = mock(KeycloakDirectory.class);
    InstitutionRegistryHolder registry = mock(InstitutionRegistryHolder.class);
    ClientAccessService access = mock(ClientAccessService.class);
    AuditEmitter audit = mock(AuditEmitter.class);
    ClientScopeService service;
    Map<String, ClientRepresentation> clients = new HashMap<>();

    ClientRepresentation client(String clientId, Map<String, String> attrs) {
        ClientRepresentation c = new ClientRepresentation();
        c.setClientId(clientId);
        c.setId("uuid-" + clientId);
        c.setAttributes(new HashMap<>(attrs));
        clients.put(clientId, c);
        return c;
    }

    @BeforeEach
    void setUp() {
        when(executors.serviceAccount()).thenReturn(exec);
        when(registry.knownSlugs()).thenReturn(Set.of("minagri", "minisante", "rdb"));
        when(directory.requireClient(any(), anyString())).thenAnswer(i -> clients.get((String) i.getArgument(1)));
        service = new ClientScopeService(executors, directory, registry, access, audit);
    }

    static ClientAccessWrites.InstitutionAccess row(String slug, boolean group, int users) {
        return new ClientAccessWrites.InstitutionAccess(slug, group, users);
    }

    @Test
    void narrowingWithUsersOnTheClientIs409AndWritesNothing() {
        client("portal", Map.of("sso.client.cross_cutting", "true", "sso.client.institutions", "minagri##minisante"));
        when(access.institutionAccessSummary(any(), eq("portal"), eq(List.of("minagri"))))
                .thenReturn(Map.of("minagri", row("minagri", false, 3)));
        var after = service.requested("portal", true, List.of("minisante"), null);
        assertThatThrownBy(() -> service.changeScope("portal", after, false, "root", null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException a = (ApiException) e;
                    assertThat(a.status().value()).isEqualTo(409);
                    assertThat(a.error()).isEqualTo("confirm_required");
                    assertThat(a.details()).containsKeys("removed_institutions", "access_at_risk", "current_scope");
                });
        assertThat(clients.get("portal").getAttributes()).containsEntry("sso.client.institutions", "minagri##minisante");
    }

    @Test
    void confirmedNarrowingWritesWithdrawsAndAudits() {
        client("portal", Map.of("sso.client.cross_cutting", "true", "sso.client.institutions", "minagri##minisante"));
        when(access.institutionAccessSummary(any(), eq("portal"), any()))
                .thenReturn(Map.of("minagri", row("minagri", true, 1)));
        when(access.removeInstitutionAccess(any(), eq("portal"), eq("minagri"), eq(null))).thenReturn(
                new ClientAccessWrites.WithdrawResult(ClientAssignment.EMPTY,
                        List.of(new ClientAccessWrites.NamedGroup("g", "minagri")), List.of("u1")));
        var after = service.requested("portal", true, List.of("minisante"), null);
        var change = service.changeScope("portal", after, true, "root", "1.1.1.1");
        assertThat(change.removedInstitutions()).containsExactly("minagri");
        assertThat(change.accessWithdrawn()).hasSize(1);
        assertThat(clients.get("portal").getAttributes()).containsEntry("sso.client.institutions", "minisante")
                .containsEntry("sso.client.owner_institution", "");
        verify(audit, times(2)).emit(any(AuditEvent.class));
    }

    @Test
    void wideningNeedsNoConfirmationAndReadsNoAccess() {
        client("portal", Map.of("sso.client.cross_cutting", "true", "sso.client.institutions", "minagri"));
        var after = service.requested("portal", true, List.of("*"), null);
        assertThat(service.changeScope("portal", after, false, "root", null).removedInstitutions()).isEmpty();
        verify(access, never()).institutionAccessSummary(any(), any(), any());
        verify(access, never()).removeInstitutionAccess(any(), any(), any(), any());
    }

    @Test
    void invalidRequestsAre400() {
        assertThatThrownBy(() -> service.requested("x", true, List.of("nowhere"), null))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThatThrownBy(() -> service.requested("x", true, List.of("minagri"), "minagri"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void visibleClientsAreOwnPlusCoveringCrossCutting() {
        var registryFile = DelegatedPermissionsLoader.loadFrom(KeycloakIT.locate("configs/delegated-permissions/templates.json"));
        var grant = registryFile.grantFor("minagri");
        InstitutionContext ctx = new InstitutionContext("a", "u", "minagri", grant, List.of("minagri"), false, List.of());
        when(directory.listClients(realm)).thenReturn(List.of(
                client("minagri-app", Map.of()),
                client("minisante-app", Map.of()),
                client("national-portal", Map.of("sso.client.cross_cutting", "true", "sso.client.institutions", "*")),
                client("other-portal", Map.of("sso.client.cross_cutting", "true", "sso.client.institutions", "rdb")),
                client("sso-admin-ui", Map.of())));
        var visible = service.clientsVisibleTo(ctx);
        assertThat(visible).extracting(ClientScopeService.VisibleClient::clientId)
                .containsExactly("minagri-app", "national-portal");
        assertThat(visible.get(0).owned()).isTrue();
        assertThat(visible.get(1).shared()).isTrue();
        assertThat(visible.get(1).owned()).isFalse();
    }

    @Test
    void appScopeOf404AndInferredOwner() {
        when(directory.findClient(any(), eq("missing"))).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.appScope("missing")).hasMessageContaining("does not exist");
        assertThat(service.scopeOf(client("minagri-kubaka", Map.of())).owner()).isEqualTo("minagri");
        verify(audit, never()).emit(argThat((AuditEvent e) -> true));
    }
}
