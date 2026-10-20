package rw.gov.risa.sso.wrapper.service.serviceaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessServiceTest;

class ServiceAccountRoleReconcilerTest {

    @Test
    void skippedWithoutAdminCredentialsAndNeverThrows() {
        var r = new ServiceAccountRoleReconciler(new ServiceAccountRoleReconciler.Properties(true, "master", "admin", ""),
                ClientAccessServiceTest.sso());
        assertThat(r.reconcileQuietly().skipped()).isEqualTo("no admin credentials");
        var unreachable = new ServiceAccountRoleReconciler(
                new ServiceAccountRoleReconciler.Properties(true, "master", "admin", "pw"), ClientAccessServiceTest.sso());
        assertThat(unreachable.reconcileQuietly().skipped()).isNotNull();
        assertThat(new ServiceAccountRoleReconciler.Properties(true, "m", "a", "secret").toString()).doesNotContain("secret");
    }

    @Test
    void addsOnlyMissingRoles() {
        RealmResource realm = mock(RealmResource.class, RETURNS_DEEP_STUBS);
        ClientRepresentation rm = new ClientRepresentation();
        rm.setId("rm");
        rm.setClientId("realm-management");
        ClientRepresentation sso = new ClientRepresentation();
        sso.setId("sso");
        sso.setClientId("sso-client");
        when(realm.clients().findByClientId("realm-management")).thenReturn(List.of(rm));
        when(realm.clients().findByClientId("sso-client")).thenReturn(List.of(sso));
        UserRepresentation sa = new UserRepresentation();
        sa.setId("sa");
        when(realm.clients().get("sso").getServiceAccountUser()).thenReturn(sa);
        RoleScopeResource scope = mock(RoleScopeResource.class);
        when(realm.users().get("sa").roles().clientLevel("rm")).thenReturn(scope);
        RoleRepresentation held = new RoleRepresentation();
        held.setName("view-users");
        when(scope.listAll()).thenReturn(List.of(held));
        when(realm.clients().get("rm").roles().get(anyString()).toRepresentation()).thenAnswer(i -> {
            RoleRepresentation r = new RoleRepresentation();
            r.setName("x");
            return r;
        });
        var result = ServiceAccountRoleReconciler.reconcile(realm, "sso-client");
        assertThat(result.alreadyHeld()).containsExactly("view-users");
        assertThat(result.granted()).hasSize(ServiceAccountRoleReconciler.REQUIRED_REALM_MANAGEMENT_ROLES.size() - 1);
        verify(scope).add(argThat(l -> l.size() == ServiceAccountRoleReconciler.REQUIRED_REALM_MANAGEMENT_ROLES.size() - 1));
        verify(scope, org.mockito.Mockito.never()).remove(any());
    }
}
