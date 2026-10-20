package rw.gov.risa.sso.wrapper.api.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;
import rw.gov.risa.sso.wrapper.service.platform.ClientAdminService.ClientView;
import rw.gov.risa.sso.wrapper.support.TestCallers;

class ClientControllerTest extends PlatformApiTestBase {

    static ClientView view(String clientId) {
        return new ClientView("uuid-1", clientId, clientId, null, true, List.of("https://app/cb"), false,
                "openid-connect", false, "client-secret", true, true, true, Map.of(), Map.of(),
                Map.of("client_id", clientId, "kind", "institution", "owner_institution", "minagri"),
                Map.of("client_id", clientId, "join_column", "email", "explicit", false));
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void listsClientsWithScopeAndJoinColumn() throws Exception {
        when(clients.list()).thenReturn(List.of(view("minagri-kubaka-web")));
        mvc.perform(get("/client/clients").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clientId").value("minagri-kubaka-web"))
                .andExpect(jsonPath("$[0].scope.kind").value("institution"))
                .andExpect(jsonPath("$[0].join_column.join_column").value("email"));
    }

    @Test
    void createsAClientWithItsScope() throws Exception {
        when(clients.create(any(), any())).thenReturn(view("minagri-app"));
        mvc.perform(json(post("/client/clients"), """
                        {"client_name":"minagri-app","redirect_uris":["https://a/cb"],"owner_institution":"minagri"}""")
                        .with(TestCallers.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("minagri-app"));
        verify(clients).create(argThat(c -> c.clientName().equals("minagri-app") && !c.crossCutting()
                && "minagri".equals(c.ownerInstitution())), argThat(c -> c.username().equals("platform.admin")));
    }

    @Test
    void createWithoutANameIs422() throws Exception {
        mvc.perform(json(post("/client/clients"), "{\"redirect_uris\":[]}").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("validation_error"));
        verifyNoInteractions(clients);
    }

    @Test
    void duplicateClientIs409() throws Exception {
        when(clients.create(any(), any())).thenThrow(ApiException.conflict(ErrorCodes.CONFLICT,
                "Client 'x' already exists.", Map.of()));
        mvc.perform(json(post("/client/clients"), "{\"client_name\":\"x\",\"redirect_uris\":[]}")
                        .with(TestCallers.superAdmin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("conflict"));
    }

    @Test
    void getUpdateDelete() throws Exception {
        when(clients.get("app")).thenReturn(view("app"));
        mvc.perform(get("/client/clients/app").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("uuid-1"));

        when(clients.update(eq("app"), any(), any())).thenReturn(view("app"));
        mvc.perform(json(put("/client/clients/app"), "{\"description\":\"d\",\"enabled\":false}")
                        .with(TestCallers.superAdmin()))
                .andExpect(status().isOk());
        verify(clients).update(eq("app"), argThat(ch -> "d".equals(ch.description())
                && Boolean.FALSE.equals(ch.enabled()) && ch.clientName() == null), any());

        mvc.perform(delete("/client/clients/app").with(TestCallers.superAdmin())).andExpect(status().isNoContent());
        verify(clients).delete(eq("app"), any());
    }

    @Test
    void unknownClientIs404Envelope() throws Exception {
        when(clients.get("nope")).thenThrow(ApiException.notFound("Client 'nope' not found."));
        mvc.perform(get("/client/clients/nope").with(TestCallers.superAdmin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void redirectAndPostLogoutUris() throws Exception {
        when(clients.updateRedirectUris(eq("app"), any(), any())).thenReturn(view("app"));
        mvc.perform(json(put("/client/clients/app/redirect-uris"), "{\"redirect_uris\":[\"https://a/*\"]}")
                .with(TestCallers.superAdmin())).andExpect(status().isOk());
        verify(clients).updateRedirectUris(eq("app"), eq(List.of("https://a/*")), any());

        mvc.perform(json(put("/client/clients/app/redirect-uris"), "{}").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());

        when(clients.updatePostLogoutRedirectUris(eq("app"), any(), any())).thenReturn(view("app"));
        mvc.perform(json(put("/client/clients/app/post-logout-redirect-uris"), "{\"post_logout_redirect_uris\":[\"+\"]}")
                .with(TestCallers.superAdmin())).andExpect(status().isOk());
        verify(clients).updatePostLogoutRedirectUris(eq("app"), eq(List.of("+")), any());
    }

    @Test
    void secretAndRotation() throws Exception {
        when(clients.secret("app")).thenReturn(Map.of("client_secret", "s1"));
        mvc.perform(get("/client/clients/app/secret").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.client_secret").value("s1"));
        when(clients.regenerateSecret(eq("app"), any())).thenReturn(Map.of("client_secret", "s2"));
        mvc.perform(post("/client/clients/app/regenerate-secret").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.client_secret").value("s2"));
    }

    @Test
    void scopeReadAndChange() throws Exception {
        when(clients.scopeState("app")).thenReturn(new ClientScopeService.ScopeState(
                Map.of("client_id", "app", "kind", "cross-cutting"),
                List.of(Map.of("institution", "minagri", "group_assigned", true, "users", 2))));
        mvc.perform(get("/client/clients/app/scope").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.kind").value("cross-cutting"))
                .andExpect(jsonPath("$.access[0].users").value(2));

        when(clients.changeScope(eq("app"), anyBoolean(), any(), any(), anyBoolean(), any()))
                .thenReturn(new ClientScopeService.ScopeChange(Map.of("kind", "cross-cutting"),
                        Map.of("kind", "cross-cutting"), List.of("minisante"), List.of()));
        mvc.perform(json(put("/client/clients/app/scope"),
                        "{\"cross_cutting\":true,\"institutions\":[\"minagri\"],\"confirm\":true}")
                        .with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed_institutions[0]").value("minisante"));
        verify(clients).changeScope(eq("app"), eq(true), eq(List.of("minagri")), isNull(), eq(true), any());
    }

    @Test
    void scopeNarrowingWithoutConfirmIs409WithTheImpact() throws Exception {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("removed_institutions", List.of("minisante"));
        details.put("access_at_risk", List.of(Map.of("institution", "minisante", "group_assigned", true, "users", 3)));
        when(clients.changeScope(eq("app"), anyBoolean(), any(), any(), eq(false), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFIRM_REQUIRED, "confirm", details));
        mvc.perform(json(put("/client/clients/app/scope"), "{\"cross_cutting\":true,\"institutions\":[\"minagri\"]}")
                        .with(TestCallers.superAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("confirm_required"))
                .andExpect(jsonPath("$.details.removed_institutions[0]").value("minisante"))
                .andExpect(jsonPath("$.details.access_at_risk[0].users").value(3));
    }

    @Test
    void joinColumnReadSetAndConfirmGate() throws Exception {
        when(clients.joinColumnState("app", true)).thenReturn(Map.of("active_sessions", 0, "choices", List.of()));
        mvc.perform(get("/client/clients/app/join-column?check=true").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active_sessions").value(0));

        when(clients.setJoinColumn(eq("app"), eq("nid"), eq(false), any())).thenThrow(new ApiException(
                HttpStatus.CONFLICT, ErrorCodes.CONFIRM_REQUIRED, "live sessions",
                Map.of("active_sessions", 4, "assigned_users", 12)));
        mvc.perform(json(put("/client/clients/app/join-column"), "{\"join_column\":\"nid\"}")
                        .with(TestCallers.superAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("confirm_required"))
                .andExpect(jsonPath("$.details.active_sessions").value(4))
                .andExpect(jsonPath("$.details.assigned_users").value(12));

        when(clients.setJoinColumn(eq("app"), eq("nid"), eq(true), any())).thenReturn(Map.of("mapper", "created"));
        mvc.perform(json(put("/client/clients/app/join-column"), "{\"join_column\":\"nid\",\"confirm\":true}")
                        .with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mapper").value("created"));

        when(clients.setJoinColumn(eq("app"), eq("nidd"), anyBoolean(), any()))
                .thenThrow(ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "unknown column"));
        mvc.perform(json(put("/client/clients/app/join-column"), "{\"join_column\":\"nidd\"}")
                .with(TestCallers.superAdmin())).andExpect(status().isBadRequest());

        when(clients.setJoinColumn(eq("app"), eq("phone"), anyBoolean(), any()))
                .thenThrow(ApiException.badGateway(ErrorCodes.KEYCLOAK_ERROR, "mapper write failed"));
        mvc.perform(json(put("/client/clients/app/join-column"), "{\"join_column\":\"phone\"}")
                .with(TestCallers.superAdmin())).andExpect(status().isBadGateway());

        mvc.perform(json(put("/client/clients/app/join-column"), "{}").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void mfaReadAndSet() throws Exception {
        when(clients.mfa("app")).thenReturn(new MfaService.ClientRequirement("app", true, null, "per_client", true));
        mvc.perform(get("/client/clients/app/mfa").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client_id").value("app"))
                .andExpect(jsonPath("$.applies_now").value(true));

        when(clients.setMfa(eq("app"), eq(false), any())).thenThrow(ApiException.forbidden(ErrorCodes.FORBIDDEN,
                "Only a super admin may change a client's two-factor requirement."));
        mvc.perform(json(put("/client/clients/app/mfa"), "{\"required\":false}").with(TestCallers.admin()))
                .andExpect(status().isForbidden());

        mvc.perform(json(put("/client/clients/app/mfa"), "{}").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void institutionAdminIsRefused() throws Exception {
        mvc.perform(get("/client/clients/app/secret").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(json(put("/client/clients/app/scope"), "{}").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(clients);
    }
}
