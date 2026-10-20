package rw.gov.risa.sso.wrapper.api.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.service.platform.UserAdminService.UserAgencyInfo;
import rw.gov.risa.sso.wrapper.service.platform.UserAdminService.UserClientInfo;
import rw.gov.risa.sso.wrapper.service.platform.UserAdminService.UserList;
import rw.gov.risa.sso.wrapper.service.platform.UserAdminService.UserView;
import rw.gov.risa.sso.wrapper.support.TestCallers;

class UserControllerTest extends PlatformApiTestBase {

    static UserView user(String id) {
        return new UserView(id, "jane", "jane@gov.rw", "Jane", "Doe", true, true, 1L,
                Map.of("institution", List.of("minagri")), List.of(new UserAgencyInfo("g1", "minagri", "/minagri")),
                List.of("employee"), List.of(new UserClientInfo("minagri-app", "App", null, true, List.of("viewer"))),
                Map.of("minagri-app", List.of("viewer")));
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void listsWithFiltersAndPaging() throws Exception {
        when(users.list(any(), eq(2), eq(10))).thenReturn(new UserList(List.of(user("u1")), 11, 2, 10, 2));
        mvc.perform(get("/users/?client_id=minagri-app&enabled=true&page=2&page_size=10").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].agencies[0].name").value("minagri"))
                .andExpect(jsonPath("$.users[0].clientRoles['minagri-app'][0]").value("viewer"))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.totalPages").value(2));
        verify(users).list(argThat(f -> "minagri-app".equals(f.clientId()) && Boolean.TRUE.equals(f.enabled())),
                eq(2), eq(10));
    }

    @Test
    void pageSizeOver100Is422() throws Exception {
        mvc.perform(get("/users/?page_size=101").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/users/?page=0").with(TestCallers.superAdmin())).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(users);
    }

    @Test
    void count() throws Exception {
        when(users.count("c", null)).thenReturn(Map.of("count", 7));
        mvc.perform(get("/users/count?client_id=c").with(TestCallers.systemAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(7));
    }

    @Test
    void createIs201AndValidated() throws Exception {
        when(users.create(any(), any())).thenReturn(user("u1"));
        mvc.perform(json(post("/users/"), """
                        {"username":"jane","email":"jane@gov.rw","firstName":"Jane","lastName":"Doe",
                         "attributes":{"national_id":["1199880012345678"]}}""").with(TestCallers.superAdmin()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("u1"));
        verify(users).create(argThat(n -> n.enabled() && !n.emailVerified()
                && n.attributes().get("national_id").get(0).equals("1199880012345678")), any());

        mvc.perform(json(post("/users/"), "{\"username\":\"ja\",\"email\":\"e\",\"firstName\":\"a\",\"lastName\":\"b\"}")
                .with(TestCallers.superAdmin())).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void identifierWritesAre400Malformedand409Taken() throws Exception {
        when(users.create(any(), any())).thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "identifier_malformed",
                "national_id must be 16 digits", Map.of("field", "national_id", "column", "nid")));
        mvc.perform(json(post("/users/"), """
                        {"username":"jane","email":"jane@gov.rw","firstName":"Jane","lastName":"Doe",
                         "attributes":{"national_id":["12"]}}""").with(TestCallers.superAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("identifier_malformed"))
                .andExpect(jsonPath("$.details.column").value("nid"));

        when(users.update(eq("u1"), any(), any())).thenThrow(new ApiException(HttpStatus.CONFLICT, "identifier_taken",
                "already held", Map.of("field", "email", "column", "email")));
        mvc.perform(json(patch("/users/u1"), "{\"email\":\"taken@gov.rw\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("identifier_taken"));
    }

    @Test
    void provision() throws Exception {
        when(users.provision(any(), eq("Secret123!"), eq(true), eq(List.of("g1")), any())).thenReturn(user("u2"));
        mvc.perform(json(post("/users/provision"), """
                        {"username":"jane","email":"jane@gov.rw","firstName":"Jane","lastName":"Doe",
                         "password":"Secret123!","temporary":true,"group_ids":["g1"]}""").with(TestCallers.superAdmin()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("u2"));

        mvc.perform(json(post("/users/provision"), """
                        {"username":"jane","email":"jane@gov.rw","firstName":"Jane","lastName":"Doe",
                         "password":"Secret123!","group_ids":[]}""").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getAndPatch() throws Exception {
        when(users.get("u1")).thenReturn(user("u1"));
        mvc.perform(get("/users/u1").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.realmRoles[0]").value("employee"));
        when(users.update(eq("u1"), any(), any())).thenReturn(user("u1"));
        mvc.perform(json(patch("/users/u1"), "{\"enabled\":false}").with(TestCallers.superAdmin()))
                .andExpect(status().isOk());
        verify(users).update(eq("u1"), argThat(c -> Boolean.FALSE.equals(c.enabled()) && c.email() == null), any());
    }

    @Test
    void resetPassword() throws Exception {
        mvc.perform(json(put("/users/u1/reset-password"), "{\"type\":\"password\",\"value\":\"NewPass123\",\"temporary\":true}")
                .with(TestCallers.superAdmin())).andExpect(status().isNoContent());
        verify(users).resetPassword(eq("u1"), eq("NewPass123"), eq(true), any());
        mvc.perform(json(put("/users/u1/reset-password"), "{\"value\":\"short\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void groupsAndBulk() throws Exception {
        GroupRepresentation g = new GroupRepresentation();
        g.setId("g1");
        g.setName("minagri");
        when(users.groups("u1")).thenReturn(List.of(g));
        mvc.perform(get("/users/u1/groups").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("minagri"));
        mvc.perform(put("/users/u1/groups/g1").with(TestCallers.superAdmin())).andExpect(status().isNoContent());
        verify(users).addToGroup(eq("u1"), eq("g1"), any());
        mvc.perform(delete("/users/u1/groups/g1").with(TestCallers.superAdmin())).andExpect(status().isNoContent());
        verify(users).removeFromGroup(eq("u1"), eq("g1"), any());
        mvc.perform(json(post("/users/u1/groups/bulk"), "{\"group_ids\":[\"g1\",\"g2\"]}").with(TestCallers.superAdmin()))
                .andExpect(status().isNoContent());
        verify(users).bulk(eq("u1"), eq(List.of("g1", "g2")), eq(true), any());
        mvc.perform(json(delete("/users/u1/groups/bulk"), "{\"group_ids\":[\"g1\"]}").with(TestCallers.superAdmin()))
                .andExpect(status().isNoContent());
        verify(users).bulk(eq("u1"), eq(List.of("g1")), eq(false), any());
        mvc.perform(json(post("/users/u1/groups/bulk"), "{\"group_ids\":[]}").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void sessions() throws Exception {
        UserSessionRepresentation s = new UserSessionRepresentation();
        s.setId("s1");
        when(users.sessions("u1")).thenReturn(List.of(s));
        mvc.perform(get("/users/u1/sessions").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("s1"));
        mvc.perform(delete("/users/u1/sessions").with(TestCallers.superAdmin())).andExpect(status().isNoContent());
        verify(users).logoutAll(eq("u1"), any());
        mvc.perform(delete("/users/u1/sessions/s1").with(TestCallers.superAdmin())).andExpect(status().isNoContent());
        verify(users).logoutSession(eq("u1"), eq("s1"), any());
    }

    @Test
    void unknownUserIs404() throws Exception {
        when(users.get("x")).thenThrow(ApiException.notFound("User not found"));
        mvc.perform(get("/users/x").with(TestCallers.superAdmin())).andExpect(status().isNotFound());
    }

    @Test
    void institutionAdminAndPlainUserAreRefused() throws Exception {
        mvc.perform(get("/users/").with(TestCallers.institutionAdmin("minagri", "admin"))).andExpect(status().isForbidden());
        mvc.perform(delete("/users/u1/sessions").with(TestCallers.plainUser())).andExpect(status().isForbidden());
        verifyNoInteractions(users);
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/users/count")).andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }
}
