package rw.gov.risa.sso.wrapper.service.institutionadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.institution.DelegatedExecution;
import rw.gov.risa.sso.wrapper.service.institution.DelegationProperties;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/** Credential selection (DELEGATED_EXECUTE_AS_CALLER) and the per-object filter, without Spring. */
class InstitutionUsersServiceTest {

    static final KeycloakExec CALLER = KeycloakExec.of(mock(RealmResource.class), true);
    static final KeycloakExec SA = KeycloakExec.of(mock(RealmResource.class), false);

    InstitutionKeycloak kc = mock(InstitutionKeycloak.class);
    KeycloakExecutors executors = mock(KeycloakExecutors.class);
    CallerPrincipal caller = TestCallers.caller("minagri.admin", java.util.Set.of("inst:minagri:admin"), "minagri");
    InstitutionContext ctx;

    @BeforeEach
    void setUp() {
        when(executors.asCaller(anyString())).thenReturn(CALLER);
        when(executors.serviceAccount()).thenReturn(SA);
        var reg = rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader.loadFrom(
                rw.gov.risa.sso.wrapper.support.KeycloakIT.locate("configs/delegated-permissions/templates.json"));
        ctx = new InstitutionContext("minagri.admin", "uid", "minagri",
                reg.templates().get("institution-admin").render("minagri"), List.of("minagri"), false, List.of());
        GroupRepresentation root = new GroupRepresentation();
        root.setId("g");
        root.setPath("/minagri");
        root.setName("minagri");
        when(kc.groupByPath(any(), eq("/minagri"))).thenReturn(Optional.of(root));
        UserRepresentation u = new UserRepresentation();
        u.setId("u1");
        u.setUsername("alice");
        when(kc.groupMembers(any(), eq("g"))).thenReturn(List.of(u));
        when(kc.userGroups(any(), eq("u1"))).thenReturn(List.of(root));
        GroupRepresentation other = new GroupRepresentation();
        other.setPath("/minisante");
        other.setName("minisante");
        when(kc.userGroups(any(), eq("u2"))).thenReturn(List.of(other));
        when(kc.getUser(any(), eq("u1"))).thenReturn(Optional.of(u));
    }

    private InstitutionUsersService service(boolean asCaller) {
        var props = new DelegationProperties("x", "y", true, true, "institution", asCaller);
        return new InstitutionUsersService(kc, new InstitutionMembers(kc), new DelegatedExecution(executors, props),
                mock(JoinKeyService.class), mock(AuditEmitter.class));
    }

    @Test
    void asCallerByDefault() {
        assertThat(service(true).listUsers(ctx, caller, null)).hasSize(1);
        verify(kc).groupMembers(argThat(KeycloakExec::asCaller), eq("g"));
    }

    @Test
    void serviceAccountWhenTheFlagIsOffButTheFilterStillApplies() {
        service(false).listUsers(ctx, caller, null);
        verify(kc).groupMembers(argThat(e -> !e.asCaller()), eq("g"));
        assertThatThrownBy(() -> service(false).getUser(ctx, caller, "u2"))
                .isInstanceOf(ApiException.class).hasMessageContaining("not a member");
    }

    @Test
    void theRoleCatalogueIsReadWithTheServiceAccountButTheWriteAsTheCaller() {
        org.keycloak.representations.idm.RoleRepresentation r = new org.keycloak.representations.idm.RoleRepresentation();
        r.setName("inst:minagri:staff");
        when(kc.realmRoles(SA)).thenReturn(List.of(r));
        Map<String, Object> out = service(true).assignRole(ctx, caller, "u1", "inst:minagri:staff", null);
        assertThat(out).containsEntry("status", "assigned");
        verify(kc).addRealmRoles(eq(CALLER), eq("u1"), any());
        verify(kc, never()).realmRoles(CALLER);
    }
}
