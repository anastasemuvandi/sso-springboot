package rw.gov.risa.sso.wrapper.service.joinkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ProcessingException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.ProtocolMappersResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumn;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumnSetting;
import rw.gov.risa.sso.wrapper.policy.joinkey.MapperReconciler;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessServiceTest;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

class JoinKeyServiceTest {

    RealmResource realm = mock(RealmResource.class, RETURNS_DEEP_STUBS);
    KeycloakExec exec = KeycloakExec.of(realm);
    KeycloakExecutors executors = mock(KeycloakExecutors.class);
    KeycloakDirectory directory = mock(KeycloakDirectory.class);
    ClientAccessService access = mock(ClientAccessService.class);
    ClientScopeService scopes = mock(ClientScopeService.class);
    AuditEmitter audit = mock(AuditEmitter.class);
    Map<String, ClientRepresentation> clients = new HashMap<>();

    JoinKeyService service(boolean enforce, boolean unset, boolean format) {
        return new JoinKeyService(executors, directory, access, scopes, new JoinKeyProperties(enforce, unset, format, 200),
                ClientAccessServiceTest.sso(), audit);
    }

    ClientRepresentation client(String clientId, Map<String, String> attrs) {
        ClientRepresentation c = new ClientRepresentation();
        c.setClientId(clientId);
        c.setId("uuid-" + clientId);
        c.setProtocol("openid-connect");
        c.setAttributes(new HashMap<>(attrs));
        clients.put(clientId, c);
        return c;
    }

    static UserRepresentation user(String id, String username, String email, Map<String, List<String>> attrs) {
        UserRepresentation u = new UserRepresentation();
        u.setId(id);
        u.setUsername(username);
        u.setEmail(email);
        u.setAttributes(attrs);
        return u;
    }

    @BeforeEach
    void setUp() {
        when(executors.serviceAccount()).thenReturn(exec);
        when(directory.requireClient(any(), anyString())).thenAnswer(i -> clients.get((String) i.getArgument(1)));
    }

    @Test
    void samlClientIs400() {
        client("saml-app", Map.of()).setProtocol("saml");
        assertThatThrownBy(() -> service(true, false, true).setJoinColumn("saml-app", JoinColumn.NID, "a", false))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
    }

    @Test
    void liveSessionsNeedConfirm() {
        client("app", Map.of());
        when(directory.activeSessionCount(any(), eq("uuid-app"))).thenReturn(2L);
        assertThatThrownBy(() -> service(true, false, true).setJoinColumn("app", JoinColumn.EMAIL, "a", false))
                .satisfies(e -> {
                    assertThat(((ApiException) e).error()).isEqualTo("confirm_required");
                    assertThat(((ApiException) e).details()).containsEntry("active_sessions", 2);
                });
    }

    @Test
    void unreadableSessionCountIsMinusOneAndDoesNotBlock() {
        client("app", Map.of());
        when(directory.activeSessionCount(any(), any())).thenThrow(new InternalServerErrorException());
        assertThat(service(true, false, true).activeSessions("app")).isEqualTo(-1);
    }

    @Test
    void failedMapperWriteIs502() {
        client("app", Map.of());
        ProtocolMappersResource mappers = mock(ProtocolMappersResource.class);
        when(realm.clients().get("uuid-app").getProtocolMappers()).thenReturn(mappers);
        when(mappers.getMappers()).thenThrow(new InternalServerErrorException());
        assertThatThrownBy(() -> service(true, false, true).reconcileMapper(realm, "uuid-app",
                JoinColumnSetting.unset("app").withColumn(JoinColumn.NID)))
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(502);
                    assertThat(e.getMessage()).contains("join_key");
                });
    }

    @Test
    void mapperUnchangedWhenItAlreadyMatches() {
        ProtocolMappersResource mappers = mock(ProtocolMappersResource.class);
        when(realm.clients().get("uuid-app").getProtocolMappers()).thenReturn(mappers);
        ProtocolMapperRepresentation m = new ProtocolMapperRepresentation();
        m.setId("m1");
        m.setName("sso-join-key");
        m.setProtocolMapper("oidc-usermodel-property-mapper");
        m.setConfig(Map.of("user.attribute", "email", "claim.name", "join_key"));
        when(mappers.getMappers()).thenReturn(List.of(m));
        assertThat(service(true, false, true).reconcileMapper(realm, "uuid-app", JoinColumnSetting.unset("app")))
                .isEqualTo(MapperReconciler.Kind.UNCHANGED);
    }

    @Test
    void gateSkipsUnsetClientsUnlessConfiguredAndExemptClients() {
        client("app", Map.of());
        assertThat(service(true, false, true).checkLogin("u", "app")).isEmpty();
        assertThat(service(false, true, true).checkLogin("u", "app")).isEmpty();
        assertThat(service(true, true, true).checkLogin("u", "sso-admin-ui")).isEmpty();
        assertThat(service(true, true, true).checkLogin(null, "app")).isEmpty();

        when(directory.findUserByUsername(any(), eq("u"))).thenReturn(Optional.of(user("1", "u", null, null)));
        when(directory.getUser(any(), eq("1"))).thenReturn(Optional.of(user("1", "u", null, null)));
        assertThat(service(true, true, true).checkLogin("u", "app").orElseThrow().resolution().error().code())
                .isEqualTo("join_key_missing");
    }

    @Test
    void gateFailsOpenOnInfrastructureErrors() {
        client("app", Map.of("sso.client.join_column", "nid"));
        when(directory.findUserByUsername(any(), any())).thenThrow(new ProcessingException("down"));
        assertThat(service(true, false, true).enforceLogin("u", "app", null, null)).isEmpty();
    }

    @Test
    void uniquenessSearchFailureSkipsTheCheck() {
        client("app", Map.of("sso.client.join_column", "nid"));
        UserRepresentation u = user("1", "u", null, Map.of("national_id", List.of("1199880012345678")));
        when(directory.findUserByUsername(any(), eq("u"))).thenReturn(Optional.of(u));
        when(directory.getUser(any(), eq("1"))).thenReturn(Optional.of(u));
        when(directory.findUsersByAttribute(any(), eq("national_id"), any(), anyInt()))
                .thenThrow(new InternalServerErrorException());
        assertThat(service(true, false, true).enforceLogin("u", "app", null, null).orElseThrow().ok()).isTrue();
    }

    @Test
    void refusalIs403WithTheJoinKeyCodeAndIsAudited() {
        client("app", Map.of("sso.client.join_column", "nid"));
        UserRepresentation u = user("1", "u", null, Map.of("national_id", List.of("1199880012345678")));
        UserRepresentation twin = user("2", "v", null, Map.of("national_id", List.of("1199 8800 1234 5678")));
        when(directory.findUserByUsername(any(), eq("u"))).thenReturn(Optional.of(u));
        when(directory.getUser(any(), eq("1"))).thenReturn(Optional.of(u));
        when(directory.findUsersByAttribute(any(), eq("national_id"), any(), anyInt())).thenReturn(List.of(u, twin));
        assertThatThrownBy(() -> service(true, false, true).enforceLogin("u", "app", "1.1.1.1", "authorization_code"))
                .satisfies(e -> {
                    ApiException a = (ApiException) e;
                    assertThat(a.status().value()).isEqualTo(403);
                    assertThat(a.error()).isEqualTo("join_key_not_unique");
                    assertThat(a.details()).containsEntry("join_column", "nid").containsEntry("claim", "join_key")
                            .containsEntry("user_attribute", "national_id");
                });
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.LOGIN_DENIED
                && "join_key_not_unique".equals(e.extra().get("join_key_error"))));
    }

    @Test
    void identifierWriteValidation() {
        var s = service(true, false, true);
        assertThatThrownBy(() -> s.validateIdentifierWrite(false, null, Map.of("phone", List.of("12")), null))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        // format validation off: a short phone passes the format check
        when(directory.findUsersByAttribute(any(), eq("phone"), any(), anyInt())).thenReturn(List.of());
        service(true, false, false).validateIdentifierWrite(false, null, Map.of("phone", List.of("12")), null);

        UserRepresentation holder = user("other", "o", "taken@gov.rw", null);
        when(directory.findUsersByEmail(any(), eq("taken@gov.rw"), anyInt())).thenReturn(List.of(holder));
        assertThatThrownBy(() -> s.validateIdentifierWrite(true, "taken@gov.rw", null, null))
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("another account").doesNotContain("'o'");
                });
        s.validateIdentifierWrite(true, "taken@gov.rw", null, "other"); // own value
        when(directory.findUsersByEmail(any(), eq("x@gov.rw"), anyInt())).thenThrow(new InternalServerErrorException());
        s.validateIdentifierWrite(true, "x@gov.rw", null, null); // search failure skipped
        s.validateIdentifierWrite(false, null, Map.of("unrelated", List.of("v")), null);
    }

    @Test
    void assignedPopulationIsCappedAndReportsTruncation() {
        when(access.assignment(any(), eq("app"))).thenReturn(new ClientAssignment(List.of("a", "b"), List.of("g"), List.of()));
        when(directory.groupMembers(any(), eq("g"), anyInt())).thenReturn(List.of(user("c", "c", null, null),
                user("a", "a", null, null), user("d", "d", null, null)));
        var p = new JoinKeyService(executors, directory, access, scopes, new JoinKeyProperties(true, false, true, 3),
                ClientAccessServiceTest.sso(), audit).assignedPopulation("app");
        assertThat(p.userIds()).containsExactly("a", "b", "c");
        assertThat(p.truncated()).isTrue();

        when(access.assignment(any(), eq("gone"))).thenThrow(ApiException.notFound("x"));
        assertThat(service(true, false, true).assignedPopulation("gone").count()).isZero();
    }

    @Test
    void backfillWorklistNeedsViewClientsAndCountsWhatTheCallerCanFix() {
        var registry = DelegatedPermissionsLoader.loadFrom(KeycloakIT.locate("configs/delegated-permissions/templates.json"));
        InstitutionContext admin = new InstitutionContext("a", "u", "minagri", registry.grantFor("minagri"),
                List.of("minagri"), false, List.of());
        ClientRepresentation own = client("minagri-app", Map.of());
        ClientRepresentation shared = client("portal", Map.of("sso.client.cross_cutting", "true"));
        when(scopes.clientsVisibleTo(admin)).thenReturn(List.of(
                new ClientScopeService.VisibleClient(own,
                        rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope.forInstitution("minagri-app", "minagri"),
                        false, true, true),
                new ClientScopeService.VisibleClient(shared,
                        rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope.forAllInstitutions("portal"),
                        true, true, false)));
        Map<String, Object> w = service(true, false, true).backfillWorklist(admin);
        assertThat(w).containsEntry("total", 2).containsEntry("outstanding", 1).containsEntry("needs_platform_admin", 1);

        var hrGrant = registry.resolveCaller(List.of("inst:minagri:hr")).get("minagri");
        InstitutionContext hr = new InstitutionContext("h", "u", "minagri", hrGrant, List.of("minagri"), false, List.of());
        assertThatThrownBy(() -> service(true, false, true).backfillWorklist(hr)).isInstanceOf(ApiException.class);
    }
}
