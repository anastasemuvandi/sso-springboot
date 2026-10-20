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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.authorization.PolicyRepresentation;
import org.keycloak.representations.idm.authorization.ScopeRepresentation;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews;
import rw.gov.risa.sso.wrapper.support.TestCallers;

class AuthorizationControllerTest extends PlatformApiTestBase {

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void clientUuidLookup() throws Exception {
        when(authz.clientUuid("app")).thenReturn(Map.of("client_uuid", "u-1"));
        mvc.perform(get("/authorization/client-id/app/uuid").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.client_uuid").value("u-1"));
    }

    @Test
    void scopesCrud() throws Exception {
        ScopeRepresentation s = new ScopeRepresentation("read");
        when(authz.scopes("cu")).thenReturn(List.of(s));
        mvc.perform(get("/authorization/clients/cu/scopes").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("read"));
        when(authz.scope("cu", "read")).thenReturn(s);
        mvc.perform(get("/authorization/clients/cu/scopes/read").with(TestCallers.superAdmin()))
                .andExpect(status().isOk());
        when(authz.createScope(eq("cu"), any(), any())).thenReturn(Map.of("id", "s1", "name", "read"));
        mvc.perform(json(post("/authorization/clients/cu/scopes"), "{\"name\":\"read\",\"display_name\":\"Read\"}")
                .with(TestCallers.superAdmin())).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("s1"));
        verify(authz).createScope(eq("cu"), argThat(sp -> "Read".equals(sp.displayName())), any());
        mvc.perform(json(put("/authorization/clients/cu/scopes/read"), "{\"name\":\"read\"}")
                .with(TestCallers.superAdmin())).andExpect(status().isNoContent());
        mvc.perform(delete("/authorization/clients/cu/scopes/read").with(TestCallers.superAdmin()))
                .andExpect(status().isNoContent());
        verify(authz).deleteScope(eq("cu"), eq("read"), any());
        mvc.perform(json(post("/authorization/clients/cu/scopes"), "{}").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void policiesCrud() throws Exception {
        PolicyRepresentation p = new PolicyRepresentation();
        p.setId("p1");
        p.setName("Only owners");
        p.setType("role");
        when(authz.policies("cu")).thenReturn(List.of(p));
        mvc.perform(get("/authorization/clients/cu/policies").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("p1"));
        when(authz.createPolicy(eq("cu"), any(), any())).thenReturn(Map.of("id", "p2"));
        mvc.perform(json(post("/authorization/clients/cu/policies"),
                        "{\"name\":\"Only owners\",\"type\":\"role\",\"roles\":[{\"id\":\"owner\",\"required\":false}]}")
                .with(TestCallers.superAdmin())).andExpect(status().isCreated());
        mvc.perform(json(put("/authorization/clients/cu/policies"), "{\"id\":\"p1\",\"name\":\"x\"}")
                .with(TestCallers.superAdmin())).andExpect(status().isNoContent());
        mvc.perform(delete("/authorization/clients/cu/policies/p1").with(TestCallers.superAdmin()))
                .andExpect(status().isNoContent());
        verify(authz).deletePolicy(eq("cu"), eq("p1"), any());
        mvc.perform(json(post("/authorization/clients/cu/policies"), "{\"name\":\"x\",\"logic\":\"MAYBE\"}")
                .with(TestCallers.superAdmin())).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void clientAccessReadIncludesDeniedUsersAndWriteReplaces() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", "app");
        body.put("enforced", true);
        body.put("users", List.of(Map.of("id", "u1", "username", "jane")));
        body.put("groups", List.of(Map.of("id", "g1", "name", "minagri")));
        body.put("denied_users", List.of(Map.of("id", "u9", "username", "bob")));
        when(authz.clientAccess("app")).thenReturn(body);
        mvc.perform(get("/authorization/clients/app/access").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].username").value("jane"))
                .andExpect(jsonPath("$.denied_users[0].id").value("u9"));

        when(authz.setClientAccess(eq("app"), any(), any(), any())).thenReturn(body);
        mvc.perform(json(put("/authorization/clients/app/access"), "{\"user_ids\":[\"u1\"],\"group_ids\":[\"g1\"]}")
                .with(TestCallers.superAdmin())).andExpect(status().isOk());
        verify(authz).setClientAccess(eq("app"), eq(List.of("u1")), eq(List.of("g1")), any());

        when(authz.setClientAccess(eq("app"), eq(List.of()), eq(List.of("g2")), any())).thenThrow(
                ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "Client 'app' is not available to institution(s)"));
        mvc.perform(json(put("/authorization/clients/app/access"), "{\"group_ids\":[\"g2\"]}")
                .with(TestCallers.superAdmin())).andExpect(status().isBadRequest());
    }

    @Test
    void userClientAccessStates() throws Exception {
        ClientAccessViews.AccessItem item = new ClientAccessViews.AccessItem("app", "cu", "App", "revoked", false,
                "denied", true, List.of(), true, true, List.of(), null);
        when(authz.userClientAccess("u1")).thenReturn(new ClientAccessViews.UserAccessView("u1", "jane",
                List.of(new ClientAccessViews.NamedRef("g1", "minagri")), List.of(item)));
        mvc.perform(get("/authorization/users/u1/client-access").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].state").value("revoked"))
                .andExpect(jsonPath("$.items[0].denied_by_name").value(true))
                .andExpect(jsonPath("$.groups[0].name").value("minagri"));
    }

    @Test
    void changeUserAccessAndTheStateGuard() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("previous_state", "not_granted");
        result.put("lifted_deny", false);
        result.put("notified", "sent");
        when(authz.changeUserAccess(eq("u1"), eq("app"), eq("grant"), eq("not_granted"), any())).thenReturn(result);
        mvc.perform(json(post("/authorization/users/u1/client-access/app"),
                        "{\"action\":\"grant\",\"expected_state\":\"not_granted\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notified").value("sent"));

        when(authz.changeUserAccess(eq("u1"), eq("app"), eq("revoke"), eq("granted"), any())).thenThrow(
                new ApiException(HttpStatus.CONFLICT, "state_changed", "changed since the page was loaded",
                        Map.of("reason", "state_changed", "current_state", "revoked")));
        mvc.perform(json(post("/authorization/users/u1/client-access/app"),
                        "{\"action\":\"revoke\",\"expected_state\":\"granted\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("state_changed"))
                .andExpect(jsonPath("$.details.current_state").value("revoked"));

        when(authz.changeUserAccess(eq("u1"), eq("app"), eq("restore"), eq("revoked"), any())).thenReturn(result);
        mvc.perform(json(post("/authorization/users/u1/client-access/app"),
                        "{\"action\":\"restore\",\"expected_state\":\"revoked\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isOk());

        mvc.perform(json(post("/authorization/users/u1/client-access/app"),
                        "{\"action\":\"delete\",\"expected_state\":\"granted\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(json(post("/authorization/users/u1/client-access/app"), "{\"action\":\"grant\"}")
                .with(TestCallers.superAdmin())).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void institutionAdminIsRefused() throws Exception {
        mvc.perform(json(post("/authorization/users/u1/client-access/app"),
                        "{\"action\":\"grant\",\"expected_state\":\"not_granted\"}")
                        .with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/authorization/clients/app/access").with(TestCallers.institutionAdmin("minagri", "hr")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(authz);
    }
}
