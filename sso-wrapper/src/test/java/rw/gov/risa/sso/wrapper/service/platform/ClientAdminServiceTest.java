package rw.gov.risa.sso.wrapper.service.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.keycloak.representations.idm.ClientRepresentation;
import org.springframework.http.HttpStatus;

import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;

class ClientAdminServiceTest {

    final PlatformMocks m = new PlatformMocks();
    final JoinKeyService joinKeys = mock(JoinKeyService.class);
    final MfaService mfa = mock(MfaService.class);
    final ClientAdminService service = new ClientAdminService(m.support, joinKeys, mfa, new PlatformProperties(false));

    AuditEvent lastAudit() {
        ArgumentCaptor<AuditEvent.Builder> c = ArgumentCaptor.forClass(AuditEvent.Builder.class);
        verify(m.audit, org.mockito.Mockito.atLeastOnce()).emit(c.capture());
        return c.getValue().build();
    }

    @Test
    void viewCarriesTheParsedScopeAndTheDefaultJoinColumn() {
        ClientAdminService.ClientView v = service.view(PlatformMocks.client("minagri-kubaka-web", Map.of()));
        assertThat(v.scope()).containsEntry("kind", "institution").containsEntry("owner_institution", "minagri")
                .containsEntry("inferred", true);
        assertThat(v.joinColumn()).containsEntry("join_column", "email").containsEntry("explicit", false);
    }

    @Test
    void createRecordsTheScopeInTheSameCallAndAudits() {
        when(m.scopes.requested("minagri-app", false, null, "minagri"))
                .thenReturn(ClientScope.forInstitution("minagri-app", "minagri"));
        Response created = Response.status(201).location(java.net.URI.create("http://kc/clients/new-uuid")).build();
        when(m.realm.clients().create(any(ClientRepresentation.class))).thenReturn(created);
        ClientRepresentation stored = PlatformMocks.client("minagri-app",
                Map.of("sso.client.owner_institution", "minagri"));
        when(m.realm.clients().get("new-uuid").toRepresentation()).thenReturn(stored);

        ClientAdminService.ClientView v = service.create(new ClientAdminService.NewClient("minagri-app",
                List.of("https://a/cb"), null, null, false, null, "minagri"), PlatformMocks.ADMIN);

        ArgumentCaptor<ClientRepresentation> rep = ArgumentCaptor.forClass(ClientRepresentation.class);
        verify(m.realm.clients()).create(rep.capture());
        assertThat(rep.getValue().getAttributes()).containsEntry("sso.client.owner_institution", "minagri")
                .containsEntry("pkce.code.challenge.method", "S256")
                .doesNotContainKey("sso.client.cross_cutting"); // blank scope values are dropped
        assertThat(rep.getValue().isPublicClient()).isFalse();
        assertThat(v.clientId()).isEqualTo("minagri-app");
        AuditEvent e = lastAudit();
        assertThat(e.type()).isEqualTo(AuditEventType.ADMIN_CLIENT_CREATED);
        assertThat(e.actor()).isEqualTo("platform.admin");
        assertThat(e.extra()).containsEntry("institution", "minagri").containsEntry("target_client", "minagri-app");
    }

    @Test
    void duplicateClientIs409() {
        when(m.scopes.requested(any(), eq(false), any(), any())).thenReturn(ClientScope.platform("x"));
        when(m.realm.clients().create(any(ClientRepresentation.class))).thenReturn(Response.status(409).build());
        assertThatThrownBy(() -> service.create(new ClientAdminService.NewClient("x", List.of(), null, null, false,
                null, null), PlatformMocks.SUPER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void samlIsGatedAndUnknownProtocolsRefused() {
        assertThatThrownBy(() -> service.create(new ClientAdminService.NewClient("x", List.of(), null, "saml", false,
                null, null), PlatformMocks.SUPER)).hasMessageContaining("SAML support is disabled");
        assertThatThrownBy(() -> service.create(new ClientAdminService.NewClient("x", List.of(), null, "cas", false,
                null, null), PlatformMocks.SUPER)).hasMessageContaining("Unsupported protocol");
        verifyNoInteractions(m.audit);
    }

    @Test
    void genericUpdateRefusesScopeAndJoinColumnAttributes() {
        ClientAdminService.ClientChanges scope = new ClientAdminService.ClientChanges(null, null, null, null, null,
                null, null, null, null, null, null, Map.of("sso.client.cross_cutting", "true"));
        assertThatThrownBy(() -> service.update("app", scope, PlatformMocks.SUPER))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("PUT /client/clients/app/scope");
                });
        ClientAdminService.ClientChanges join = new ClientAdminService.ClientChanges(null, null, null, null, null,
                null, null, null, null, null, null, Map.of("sso.client.join_column", "nid"));
        assertThatThrownBy(() -> service.update("app", join, PlatformMocks.SUPER))
                .hasMessageContaining("/join-column");
        verify(m.realm, never()).clients();
    }

    @Test
    void updateMergesAttributesAndAuditsTheFieldsSent() {
        ClientRepresentation c = PlatformMocks.client("app", Map.of("pkce.code.challenge.method", "S256"));
        when(m.directory.findClient(m.realm, "app")).thenReturn(Optional.of(c));
        when(m.realm.clients().get("uuid-app").toRepresentation()).thenReturn(c);
        service.update("app", new ClientAdminService.ClientChanges("New name", null, false, null, null, null, null,
                null, null, null, null, Map.of("custom", "1")), PlatformMocks.SUPER);
        ArgumentCaptor<ClientRepresentation> rep = ArgumentCaptor.forClass(ClientRepresentation.class);
        verify(m.realm.clients().get("uuid-app")).update(rep.capture());
        assertThat(rep.getValue().getAttributes()).containsEntry("pkce.code.challenge.method", "S256")
                .containsEntry("custom", "1");
        assertThat(rep.getValue().getName()).isEqualTo("New name");
        assertThat(rep.getValue().isEnabled()).isFalse();
        assertThat(lastAudit().extra()).containsEntry("fields", List.of("client_name", "enabled", "attributes"));
    }

    @Test
    void postLogoutUrisAreJoinedWithHashes() {
        ClientRepresentation c = PlatformMocks.client("app", Map.of());
        when(m.directory.findClient(m.realm, "app")).thenReturn(Optional.of(c));
        when(m.realm.clients().get("uuid-app").toRepresentation()).thenReturn(c);
        service.updatePostLogoutRedirectUris("app", List.of(" https://a/out ", "", "https://b/out"), PlatformMocks.SUPER);
        ArgumentCaptor<ClientRepresentation> rep = ArgumentCaptor.forClass(ClientRepresentation.class);
        verify(m.realm.clients().get("uuid-app")).update(rep.capture());
        assertThat(rep.getValue().getAttributes()).containsEntry("post.logout.redirect.uris", "https://a/out##https://b/out");
    }

    @Test
    void unknownClientIs404AndAUuidIsAccepted() {
        when(m.directory.findClient(m.realm, "nope")).thenReturn(Optional.empty());
        when(m.realm.clients().get("nope").toRepresentation()).thenThrow(new jakarta.ws.rs.NotFoundException());
        assertThatThrownBy(() -> service.get("nope"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));

        ClientRepresentation byUuid = PlatformMocks.client("app", Map.of());
        when(m.directory.findClient(m.realm, "uuid-app")).thenReturn(Optional.empty());
        when(m.realm.clients().get("uuid-app").toRepresentation()).thenReturn(byUuid);
        assertThat(service.get("uuid-app").clientId()).isEqualTo("app");
    }

    @Test
    void publicClientHasNoSecret() {
        ClientRepresentation c = PlatformMocks.client("spa", Map.of());
        c.setPublicClient(true);
        when(m.directory.findClient(m.realm, "spa")).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.secret("spa")).hasMessageContaining("public client");
    }

    @Test
    void joinColumnTypoIs400BeforeAnyWrite() {
        assertThatThrownBy(() -> service.setJoinColumn("app", "nidd", false, PlatformMocks.SUPER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(joinKeys);
    }

    @Test
    void joinColumnConfirmGateIsPassedThrough() {
        ClientRepresentation c = PlatformMocks.client("app", Map.of());
        when(m.directory.findClient(m.realm, "app")).thenReturn(Optional.of(c));
        when(joinKeys.setJoinColumn(eq("app"), any(), eq("platform.super"), eq(false))).thenThrow(new ApiException(
                HttpStatus.CONFLICT, "confirm_required", "live", Map.of("active_sessions", 3, "assigned_users", 0)));
        assertThatThrownBy(() -> service.setJoinColumn("app", "nid", false, PlatformMocks.SUPER))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.error()).isEqualTo("confirm_required");
                    assertThat(e.details()).containsEntry("active_sessions", 3);
                });
        verifyNoInteractions(m.audit);
    }

    @Test
    void onlyASuperAdminSetsAClientsMfa() {
        assertThatThrownBy(() -> service.setMfa("app", false, PlatformMocks.ADMIN))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(mfa);

        ClientRepresentation c = PlatformMocks.client("app", Map.of());
        when(m.directory.findClient(m.realm, "app")).thenReturn(Optional.of(c));
        service.setMfa("app", false, PlatformMocks.SUPER);
        verify(mfa).setClientRequirement(eq("app"), eq(false), eq("platform.super"), any(), eq(null));
    }

    @Test
    void scopeChangeValidatesThenDelegatesTheTwoStep() {
        ClientRepresentation c = PlatformMocks.client("app", Map.of());
        when(m.directory.findClient(m.realm, "app")).thenReturn(Optional.of(c));
        ClientScope after = ClientScope.forSelected("app", List.of("minagri"));
        when(m.scopes.requested("app", true, List.of("minagri"), null)).thenReturn(after);
        service.changeScope("app", true, List.of("minagri"), null, false, PlatformMocks.SUPER);
        verify(m.scopes).changeScope(eq("app"), eq(after), eq(false), eq("platform.super"), any());
    }
}
