package rw.gov.risa.sso.wrapper.api.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessPolicy;
import rw.gov.risa.sso.wrapper.service.clientaccess.AccessPolicyStore;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.support.TestCallers;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/** {@code /admin/*}: real Keycloak data (the reference was mock), the admin-tier gate, super-admin system users. */
@WrapperTest
class AdminViewsControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuditEmitter audit;
    @MockitoBean KeycloakAdminFactory factory;
    @MockitoBean KeycloakExecutors executors;
    @MockitoBean AccessPolicyStore policies;

    RealmResource realm;

    static UserRepresentation user(String id, String username) {
        UserRepresentation u = new UserRepresentation();
        u.setId(id);
        u.setUsername(username);
        u.setEnabled(true);
        return u;
    }

    @BeforeEach
    void realm() {
        realm = mock(RealmResource.class, RETURNS_DEEP_STUBS);
        when(executors.serviceAccount()).thenReturn(KeycloakExec.of(realm));
    }

    @Test
    void institutionAdminIsRefused() throws Exception {
        mvc.perform(get("/admin/agencies/minagri/dashboard").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/system/clients").with(TestCallers.plainUser())).andExpect(status().isForbidden());
    }

    @Test
    void agencyDashboardCountsTheWholeGroupTree() throws Exception {
        GroupRepresentation root = new GroupRepresentation();
        root.setId("g-root");
        root.setName("minagri");
        root.setPath("/minagri");
        GroupRepresentation planning = new GroupRepresentation();
        planning.setId("g-plan");
        planning.setName("planning");
        planning.setPath("/minagri/planning");
        when(realm.getGroupByPath("/minagri")).thenReturn(root);
        when(realm.groups().group("g-root").getSubGroups(anyInt(), anyInt(), anyBoolean())).thenReturn(List.of(planning));
        when(realm.groups().group("g-plan").getSubGroups(anyInt(), anyInt(), anyBoolean())).thenReturn(List.of());
        when(realm.groups().group("g-root").members(anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(user("u1", "a"), user("u2", "b")));
        when(realm.groups().group("g-plan").members(anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(user("u2", "b"), user("u3", "c")));
        ClientRepresentation app = new ClientRepresentation();
        app.setClientId("minagri-kubaka-web");
        app.setAttributes(Map.of());
        ClientRepresentation other = new ClientRepresentation();
        other.setClientId("minisante-hmis");
        other.setAttributes(Map.of());
        when(realm.clients().findAll()).thenReturn(List.of(app, other));
        mvc.perform(get("/admin/agencies/MINAGRI/dashboard").with(TestCallers.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agency_code").value("minagri"))
                .andExpect(jsonPath("$.user_count").value(3))
                .andExpect(jsonPath("$.groups[0].name").value("planning"))
                .andExpect(jsonPath("$.groups[0].member_count").value(2))
                .andExpect(jsonPath("$.services[0]").value("minagri-kubaka-web"))
                .andExpect(jsonPath("$.services.length()").value(1));
    }

    @Test
    void anUnknownAgencyIs404() throws Exception {
        when(realm.getGroupByPath("/nowhere")).thenThrow(new jakarta.ws.rs.NotFoundException());
        when(realm.groups().groups(eq("nowhere"), anyInt(), anyInt(), anyBoolean())).thenReturn(List.of());
        mvc.perform(get("/admin/agencies/nowhere/groups").with(TestCallers.superAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void clientUsersAndDashboardAreReal() throws Exception {
        ClientRepresentation c = new ClientRepresentation();
        c.setId("c1");
        c.setClientId("minagri-kubaka-web");
        c.setName("Kubaka");
        when(realm.clients().findByClientId("minagri-kubaka-web")).thenReturn(List.of(c));
        RoleRepresentation viewer = new RoleRepresentation();
        viewer.setName("viewer");
        when(realm.clients().get("c1").roles().list()).thenReturn(List.of(viewer));
        when(realm.clients().get("c1").roles().get("viewer").getUserMembers(anyInt(), anyInt()))
                .thenReturn(List.of(user("u1", "alice")));
        when(policies.read(any(), eq("c1"))).thenReturn(List.of(
                new AccessPolicy("p", "sso-access-users", "user", "POSITIVE", Map.of("users", "[\"u1\",\"u2\"]"))));
        when(realm.users().get("u2").toRepresentation()).thenReturn(user("u2", "bob"));
        when(realm.getEvents(anyList(), eq("minagri-kubaka-web"), isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(realm.clients().get("c1").getApplicationSessionCount()).thenReturn(Map.of("count", 4));
        mvc.perform(get("/admin/clients/minagri-kubaka-web/users").with(TestCallers.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].roles[0]").value("viewer"))
                .andExpect(jsonPath("$[1].username").value("bob"))
                .andExpect(jsonPath("$[1].status").value("active"));
        mvc.perform(get("/admin/clients/minagri-kubaka-web/dashboard").with(TestCallers.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_count").value(2))
                .andExpect(jsonPath("$.active_sessions").value(4))
                .andExpect(jsonPath("$.permissions[0]").value("minagri-kubaka-web:viewer"));
    }

    @Test
    void assigningAnUnknownClientRoleIs400AndAKnownOneIsAudited() throws Exception {
        ClientRepresentation c = new ClientRepresentation();
        c.setId("c1");
        c.setClientId("minagri-kubaka-web");
        when(realm.clients().findByClientId("minagri-kubaka-web")).thenReturn(List.of(c));
        RoleRepresentation viewer = new RoleRepresentation();
        viewer.setName("viewer");
        when(realm.clients().get("c1").roles().list()).thenReturn(List.of(viewer));
        when(realm.users().get("u1").toRepresentation()).thenReturn(user("u1", "alice"));
        mvc.perform(post("/admin/clients/minagri-kubaka-web/users/u1/roles").with(TestCallers.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"ghost\"]}"))
                .andExpect(status().isBadRequest());
        verify(audit, never()).emit(any(AuditEvent.class));
        mvc.perform(post("/admin/clients/minagri-kubaka-web/users/u1/roles").with(TestCallers.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"viewer\"]}"))
                .andExpect(status().isOk());
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.ADMIN_ROLE_ASSIGNED
                && "platform.super".equals(e.actor())));
    }

    @Test
    void systemUsersIsSuperAdminOnly() throws Exception {
        mvc.perform(get("/admin/system/users").with(TestCallers.admin())).andExpect(status().isForbidden());
        when(realm.users().search(isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt(), isNull(), anyBoolean()))
                .thenReturn(List.of(user("u1", "alice")));
        when(realm.users().count()).thenReturn(1);
        mvc.perform(get("/admin/system/users").param("limit", "10").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].username").value("alice"))
                .andExpect(jsonPath("$.pagination.total").value(1))
                .andExpect(jsonPath("$.pagination.limit").value(10));
        mvc.perform(get("/admin/system/users").param("limit", "1000").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void creatingADepartmentNeedsAName() throws Exception {
        mvc.perform(post("/admin/agencies/minagri/groups").with(TestCallers.superAdmin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a/b\"}"))
                .andExpect(status().isBadRequest());
        verify(audit, never()).emit(any(AuditEvent.class));
    }
}
