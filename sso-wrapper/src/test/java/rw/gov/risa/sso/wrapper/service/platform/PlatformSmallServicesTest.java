package rw.gov.risa.sso.wrapper.service.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.http.HttpStatus;

import rw.gov.risa.sso.wrapper.error.ApiException;

/** Agencies and roles. */
class PlatformSmallServicesTest {

    @Nested
    class Agencies {
        final PlatformMocks m = new PlatformMocks();
        final AgencyAdminService service = new AgencyAdminService(m.support);

        @Test
        void groupTypeFromAttributeThenName() {
            assertThat(AgencyAdminService.groupType("x", Map.of("type", List.of("Agency")))).isEqualTo("agency");
            assertThat(AgencyAdminService.groupType("MINAGRI", Map.of())).isEqualTo("ministry");
            assertThat(AgencyAdminService.groupType("IT Department", Map.of())).isEqualTo("department");
            assertThat(AgencyAdminService.groupType("RISA", Map.of())).isEqualTo("other");
        }

        @Test
        void subgroupsAreReadFromChildrenNotSubGroups() {
            GroupRepresentation parent = new GroupRepresentation();
            parent.setId("g1");
            parent.setSubGroups(List.of()); // Keycloak 26: always empty
            GroupRepresentation child = new GroupRepresentation();
            child.setId("g2");
            child.setName("planning");
            child.setPath("/minagri/planning");
            when(m.directory.getGroup(m.realm, "g1")).thenReturn(Optional.of(parent));
            when(m.directory.groupChildren(m.realm, "g1")).thenReturn(List.of(child));
            assertThat(service.subgroups("g1")).containsExactly(
                    new AgencyAdminService.SubgroupInfo("g2", "planning", "/minagri/planning"));
        }

        @Test
        void mfaExemptOnAnAgencyIsRefusedBeforeKeycloak() {
            assertThatThrownBy(() -> service.create("X", List.of("mfa-exempt"), null, null, PlatformMocks.SUPER))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
            verifyNoInteractions(m.realm, m.audit);
        }

        @Test
        void unknownGroupIs404() {
            when(m.directory.getGroup(m.realm, "nope")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.get("nope"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        void addMemberAuditsTheInstitutionOfTheGroup() {
            GroupRepresentation g = new GroupRepresentation();
            g.setId("g1");
            g.setName("minagri");
            g.setPath("/minagri");
            UserRepresentation u = new UserRepresentation();
            u.setId("u1");
            when(m.directory.getGroup(m.realm, "g1")).thenReturn(Optional.of(g));
            when(m.directory.getUser(m.realm, "u1")).thenReturn(Optional.of(u));
            service.addMember("g1", "u1", PlatformMocks.ADMIN);
            verify(m.realm.users().get("u1")).joinGroup("g1");
            var captor = org.mockito.ArgumentCaptor.forClass(rw.gov.risa.sso.wrapper.audit.AuditEvent.Builder.class);
            verify(m.audit).emit(captor.capture());
            var e = captor.getValue().build();
            assertThat(e.actor()).isEqualTo("platform.admin");
            assertThat(e.extra()).containsEntry("institution", "minagri").containsEntry("target_user", "u1");
        }
    }

    @Nested
    class Roles {
        final PlatformMocks m = new PlatformMocks();
        final RoleAdminService service = new RoleAdminService(m.support);

        @Test
        void unknownRealmRoleIs400AndNothingIsMapped() {
            when(m.directory.getUser(m.realm, "u1")).thenReturn(Optional.of(new UserRepresentation()));
            when(m.directory.realmRole(m.realm, "ghost")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.changeUserRealmRoles("u1", List.of("ghost"), true, PlatformMocks.SUPER))
                    .hasMessageContaining("Unknown realm role(s): ['ghost']");
            verify(m.realm.users().get("u1").roles().realmLevel(), never()).add(any());
            verifyNoInteractions(m.audit);
        }

        @Test
        void mfaExemptIsNeverAssignableHere() {
            assertThatThrownBy(() -> service.changeUserRealmRoles("u1", List.of("mfa-exempt"), true, PlatformMocks.SUPER))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
            verifyNoInteractions(m.realm);
        }

        @Test
        void assignAuditsTheInstitutionOfNamespacedRoles() {
            UserRepresentation u = new UserRepresentation();
            u.setId("u1");
            when(m.directory.getUser(m.realm, "u1")).thenReturn(Optional.of(u));
            RoleRepresentation r = new RoleRepresentation();
            r.setName("inst:minagri:hr");
            when(m.directory.realmRole(m.realm, "inst:minagri:hr")).thenReturn(Optional.of(r));
            service.changeUserRealmRoles("u1", List.of("inst:minagri:hr"), true, PlatformMocks.SUPER);
            verify(m.realm.users().get("u1").roles().realmLevel()).add(List.of(r));
            var captor = org.mockito.ArgumentCaptor.forClass(rw.gov.risa.sso.wrapper.audit.AuditEvent.Builder.class);
            verify(m.audit).emit(captor.capture());
            assertThat(captor.getValue().build().extra()).containsEntry("institution", "minagri")
                    .containsEntry("scope", "realm");
        }

        @Test
        void clientUuidIsNullForAnUnknownClient() {
            when(m.directory.findClient(m.realm, "nope")).thenReturn(Optional.empty());
            assertThat(service.clientUuid("nope")).containsEntry("client_uuid", null);
            ClientRepresentation c = PlatformMocks.client("app", Map.of());
            when(m.directory.findClient(m.realm, "app")).thenReturn(Optional.of(c));
            assertThat(service.clientUuid("app")).containsEntry("client_uuid", "uuid-app");
        }

        @Test
        void statisticsSplitsCompositeRolesAsSystemRoles() {
            RoleRepresentation composite = new RoleRepresentation();
            composite.setName("default-roles-x");
            composite.setComposite(true);
            RoleRepresentation plain = new RoleRepresentation();
            plain.setName("employee");
            plain.setComposite(false);
            when(m.realm.roles().list()).thenReturn(List.of(composite, plain));
            ClientRepresentation sec = PlatformMocks.client("security-admin-console", Map.of());
            ClientRepresentation app = PlatformMocks.client("app", Map.of());
            when(m.directory.listClients(m.realm)).thenReturn(List.of(sec, app));
            when(m.realm.clients().get("uuid-app").roles().list()).thenReturn(List.of(plain));
            RoleAdminService.RoleStatistics s = service.statistics();
            assertThat(s.totalClients()).isEqualTo(1);
            assertThat(s.clientRolesCount()).isEqualTo(1);
            assertThat(s.systemRoles()).containsExactly("default-roles-x");
            assertThat(s.customRoles()).containsExactly("employee");
        }
    }
}
