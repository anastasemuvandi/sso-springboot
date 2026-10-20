package rw.gov.risa.sso.wrapper.api.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.service.platform.RoleAdminService.RoleClientInfo;
import rw.gov.risa.sso.wrapper.service.platform.RoleAdminService.RoleStatistics;
import rw.gov.risa.sso.wrapper.service.platform.RoleAdminService.RoleView;
import rw.gov.risa.sso.wrapper.service.platform.RoleAdminService.UserRoleInfo;
import rw.gov.risa.sso.wrapper.support.TestCallers;

class RoleControllerTest extends PlatformApiTestBase {

    static RoleView role(String name, boolean client) {
        return new RoleView("rid-" + name, name, null, false, client, "container", null);
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void realmRolesCrud() throws Exception {
        when(roles.realmRoles()).thenReturn(List.of(role("employee", false)));
        mvc.perform(get("/roles/realm-roles").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].client_role").value(false))
                .andExpect(jsonPath("$[0].container_id").value("container"));

        when(roles.createRealmRole(any(), any())).thenReturn(Map.of("message", "ok"));
        mvc.perform(json(post("/roles/realm-roles"), "{\"name\":\"auditor\",\"description\":\"d\"}")
                .with(TestCallers.superAdmin())).andExpect(status().isCreated());
        verify(roles).createRealmRole(argThat(s -> s.name().equals("auditor") && !s.composite()), any());

        mvc.perform(json(post("/roles/realm-roles"), "{\"name\":\"\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());

        when(roles.updateRealmRole(eq("auditor"), any(), any())).thenReturn(Map.of("message", "ok"));
        mvc.perform(json(put("/roles/realm-roles/auditor"), "{\"name\":\"auditor\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isOk());
        when(roles.deleteRealmRole(eq("auditor"), any())).thenReturn(Map.of("message", "ok"));
        mvc.perform(delete("/roles/realm-roles/auditor").with(TestCallers.superAdmin())).andExpect(status().isOk());

        when(roles.createRealmRole(any(), any())).thenThrow(ApiException.conflict(ErrorCodes.CONFLICT, "exists", Map.of()));
        mvc.perform(json(post("/roles/realm-roles"), "{\"name\":\"auditor\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isConflict());
    }

    @Test
    void clientRolesCrud() throws Exception {
        when(roles.clientRoles("cuuid")).thenReturn(List.of(role("viewer", true)));
        mvc.perform(get("/roles/clients/cuuid/roles").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].client_role").value(true));
        when(roles.createClientRole(eq("cuuid"), any(), any())).thenReturn(Map.of("message", "ok"));
        mvc.perform(json(post("/roles/clients/cuuid/roles"), "{\"name\":\"viewer\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isCreated());
        when(roles.updateClientRole(eq("cuuid"), eq("viewer"), any(), any())).thenReturn(Map.of("message", "ok"));
        mvc.perform(json(put("/roles/clients/cuuid/roles/viewer"), "{\"name\":\"viewer\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isOk());
        when(roles.deleteClientRole(eq("cuuid"), eq("viewer"), any())).thenReturn(Map.of("message", "ok"));
        mvc.perform(delete("/roles/clients/cuuid/roles/viewer").with(TestCallers.superAdmin())).andExpect(status().isOk());
    }

    @Test
    void userRoleMappings() throws Exception {
        when(roles.userRoles("u1")).thenReturn(new UserRoleInfo("u1", List.of(role("employee", false)),
                Map.of("app", List.of(role("viewer", true)))));
        mvc.perform(get("/roles/users/u1/roles").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value("u1"))
                .andExpect(jsonPath("$.client_roles.app[0].name").value("viewer"));

        when(roles.changeUserRealmRoles(eq("u1"), eq(List.of("employee")), eq(true), any())).thenReturn(Map.of("message", "ok"));
        mvc.perform(json(post("/roles/users/u1/realm-roles"), "{\"user_id\":\"u1\",\"roles\":[{\"name\":\"employee\"}]}")
                .with(TestCallers.superAdmin())).andExpect(status().isOk());
        when(roles.changeUserRealmRoles(eq("u1"), eq(List.of("employee")), eq(false), any())).thenReturn(Map.of("message", "ok"));
        mvc.perform(json(delete("/roles/users/u1/realm-roles"), "{\"roles\":[{\"name\":\"employee\"}]}")
                .with(TestCallers.superAdmin())).andExpect(status().isOk());

        when(roles.changeUserClientRoles(eq("u1"), eq("cuuid"), eq(List.of("viewer")), eq(true), any()))
                .thenReturn(Map.of("message", "ok"));
        mvc.perform(json(post("/roles/users/u1/client-roles/cuuid"), "{\"roles\":[{\"id\":\"x\",\"name\":\"viewer\"}]}")
                .with(TestCallers.superAdmin())).andExpect(status().isOk());
        when(roles.changeUserClientRoles(eq("u1"), eq("cuuid"), eq(List.of("viewer")), eq(false), any()))
                .thenReturn(Map.of("message", "ok"));
        mvc.perform(json(delete("/roles/users/u1/client-roles/cuuid"), "{\"roles\":[{\"name\":\"viewer\"}]}")
                .with(TestCallers.superAdmin())).andExpect(status().isOk());

        mvc.perform(json(post("/roles/users/u1/realm-roles"), "{\"roles\":[]}").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(json(post("/roles/users/u1/realm-roles"), "{\"roles\":[{\"id\":\"x\"}]}").with(TestCallers.superAdmin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mfaExemptIsNotAssignableHere() throws Exception {
        when(roles.changeUserRealmRoles(eq("u1"), eq(List.of("mfa-exempt")), eq(true), any()))
                .thenThrow(ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "'mfa-exempt' cannot be assigned"));
        mvc.perform(json(post("/roles/users/u1/realm-roles"), "{\"roles\":[{\"name\":\"mfa-exempt\"}]}")
                .with(TestCallers.superAdmin())).andExpect(status().isBadRequest());
    }

    @Test
    void catalogue() throws Exception {
        when(roles.statistics()).thenReturn(new RoleStatistics(3, 4, 2, List.of("default-roles"), List.of("a")));
        mvc.perform(get("/roles/statistics").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.realm_roles_count").value(3));
        when(roles.clients()).thenReturn(List.of(new RoleClientInfo("cuuid", "app", "App", null)));
        mvc.perform(get("/roles/clients").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].client_id").value("app"));
        Map<String, String> none = new HashMap<>();
        none.put("client_uuid", null);
        when(roles.clientUuid("nope")).thenReturn(none);
        mvc.perform(get("/roles/client-id/nope/uuid").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.client_uuid").isEmpty());
    }

    @Test
    void superAdminPassesWhereTheReferenceWantedAdmin() throws Exception {
        when(roles.realmRoles()).thenReturn(List.of());
        mvc.perform(get("/roles/realm-roles").with(TestCallers.superAdmin())).andExpect(status().isOk());
        mvc.perform(get("/roles/realm-roles").with(TestCallers.systemAdmin())).andExpect(status().isOk());
        mvc.perform(get("/roles/realm-roles").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
    }

    @Test
    void institutionAdminCannotMapRoles() throws Exception {
        mvc.perform(json(post("/roles/users/u1/realm-roles"), "{\"roles\":[{\"name\":\"employee\"}]}")
                .with(TestCallers.institutionAdmin("minagri", "admin"))).andExpect(status().isForbidden());
        verifyNoInteractions(roles);
    }
}
