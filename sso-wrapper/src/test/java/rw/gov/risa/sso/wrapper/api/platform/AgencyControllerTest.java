package rw.gov.risa.sso.wrapper.api.platform;

import static org.mockito.ArgumentMatchers.any;
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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.service.platform.AgencyAdminService.AgencyView;
import rw.gov.risa.sso.wrapper.service.platform.AgencyAdminService.MemberView;
import rw.gov.risa.sso.wrapper.service.platform.AgencyAdminService.SubgroupInfo;
import rw.gov.risa.sso.wrapper.support.TestCallers;

class AgencyControllerTest extends PlatformApiTestBase {

    static AgencyView agency() {
        return new AgencyView("g1", "minagri", "/minagri", "ministry", "MINAGRI", Map.of(), 2, 1, 1,
                List.of(new MemberView("u1", "jane", null, null, null, true, true, null)), 1, List.of("minagri-app"),
                Map.of("minagri-app", List.of("viewer")), List.of("employee"), 1,
                List.of(new SubgroupInfo("g2", "planning", "/minagri/planning")), null);
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void listCountMetrics() throws Exception {
        when(agencies.list()).thenReturn(List.of(agency()));
        mvc.perform(get("/agency/").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subGroups[0].path").value("/minagri/planning"))
                .andExpect(jsonPath("$[0].totalSubgroups").value(1))
                .andExpect(jsonPath("$[0].activeMembers").value(1));
        when(agencies.count()).thenReturn(Map.of("count", 2L));
        mvc.perform(get("/agency/count").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(2));
        when(agencies.metrics()).thenReturn(Map.of("totalAgencies", 2, "totalUsers", 5));
        mvc.perform(get("/agency/metrics").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalUsers").value(5));
    }

    @Test
    void createUpdateDelete() throws Exception {
        when(agencies.create(eq("MININFRA"), any(), any(), any(), any())).thenReturn(agency());
        mvc.perform(json(post("/agency/"), "{\"name\":\"MININFRA\",\"realmRoles\":[\"employee\"]}")
                .with(TestCallers.superAdmin())).andExpect(status().isCreated());
        verify(agencies).create(eq("MININFRA"), eq(List.of("employee")), isNull(), isNull(), any());

        mvc.perform(json(post("/agency/"), "{}").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());

        when(agencies.update(eq("g1"), eq("renamed"), isNull(), isNull(), isNull(), any())).thenReturn(agency());
        mvc.perform(json(put("/agency/g1"), "{\"name\":\"renamed\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isOk());

        mvc.perform(delete("/agency/g1").with(TestCallers.superAdmin())).andExpect(status().isNoContent());
        verify(agencies).delete(eq("g1"), any());
    }

    @Test
    void mfaExemptRoleOnAnAgencyIs400() throws Exception {
        when(agencies.create(any(), any(), any(), any(), any())).thenThrow(ApiException.badRequest(
                ErrorCodes.INVALID_REQUEST, "'mfa-exempt' cannot be assigned through an agency (group) role assignment."));
        mvc.perform(json(post("/agency/"), "{\"name\":\"X\",\"realmRoles\":[\"mfa-exempt\"]}")
                .with(TestCallers.superAdmin())).andExpect(status().isBadRequest());
    }

    @Test
    void getOneAnd404() throws Exception {
        when(agencies.get("g1")).thenReturn(agency());
        mvc.perform(get("/agency/g1").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.members[0].username").value("jane"));
        when(agencies.get("nope")).thenThrow(ApiException.notFound("Group 'nope' not found"));
        mvc.perform(get("/agency/nope").with(TestCallers.superAdmin())).andExpect(status().isNotFound());
    }

    @Test
    void subgroupsAreTheChildren() throws Exception {
        when(agencies.subgroups("g1")).thenReturn(List.of(new SubgroupInfo("g2", "planning", "/minagri/planning")));
        mvc.perform(get("/agency/g1/subgroups").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("g2"));
        when(agencies.createSubgroup(eq("g1"), eq("IT"), any(), any()))
                .thenReturn(new SubgroupInfo("g3", "IT", "/minagri/IT"));
        mvc.perform(json(post("/agency/g1/subgroups"), "{\"name\":\"IT\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.path").value("/minagri/IT"));
    }

    @Test
    void membersUseTheMemberAddSchema() throws Exception {
        when(agencies.members("g1")).thenReturn(List.of(new MemberView("u1", "jane", null, null, null, false, null, null)));
        mvc.perform(get("/agency/g1/members").with(TestCallers.superAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].enabled").value(false));
        mvc.perform(json(post("/agency/g1/members"), "{\"userId\":\"u1\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isNoContent());
        verify(agencies).addMember(eq("g1"), eq("u1"), any());
        mvc.perform(json(post("/agency/g1/members"), "{\"user_id\":\"u1\"}").with(TestCallers.superAdmin()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/agency/g1/members/u1").with(TestCallers.superAdmin())).andExpect(status().isNoContent());
        verify(agencies).removeMember(eq("g1"), eq("u1"), any());
    }

    @Test
    void roles() throws Exception {
        when(agencies.addRoles(eq("g1"), any(), any(), any())).thenReturn(agency());
        mvc.perform(json(post("/agency/g1/roles"), "{\"realmRoles\":[\"employee\"],\"clientRoles\":{\"app\":[\"r\"]}}")
                .with(TestCallers.superAdmin())).andExpect(status().isOk());
        verify(agencies).addRoles(eq("g1"), eq(List.of("employee")), eq(Map.of("app", List.of("r"))), any());
        when(agencies.removeRoles(eq("g1"), any(), any(), any())).thenReturn(agency());
        mvc.perform(json(delete("/agency/g1/roles"), "{\"realmRoles\":[\"employee\"]}").with(TestCallers.superAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void institutionAdminIsRefused() throws Exception {
        mvc.perform(get("/agency/g1/members").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(agencies);
    }
}
