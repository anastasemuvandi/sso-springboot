package rw.gov.risa.sso.wrapper.isolation;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminMvcSupport;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/** SPEC §7.1 — granting a broad admin role (or appointing a co-admin) through this layer is blocked. */
class BroadRoleGrantIsBlockedIsolationTest extends InstitutionAdminMvcSupport {

    @ParameterizedTest
    @ValueSource(strings = {"super_admin", "admin", "system_admin", "mfa-exempt", "default-roles-government-internal"})
    void aLocalAdminCannotGrantABroadRoleNotEvenToThemselves(String role) throws Exception {
        for (String target : new String[] {"u-a1", "u-a2"}) {
            mvc.perform(post("/institution-admin/users/" + target + "/roles").with(TestCallers.institutionAdmin(A, "admin"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                    .andExpect(status().isForbidden());
        }
        verify(kc, never()).addRealmRoles(any(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"super_admin", "admin", "inst:minagri:admin"})
    void broadRolesAreNeverOffered(String role) throws Exception {
        mvc.perform(get("/institution-admin/roles").with(TestCallers.institutionAdmin(A, "admin")))
                .andExpect(jsonPath("$[*].name", not(hasItem(role))));
    }

    @Test
    void appointingACoAdminIsBlocked() throws Exception {
        mvc.perform(post("/institution-admin/users/u-a1/roles").with(TestCallers.institutionAdmin(A, "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"inst:minagri:admin\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theGuardrailHoldsForAPlatformAdminActingLocally() throws Exception {
        mvc.perform(post("/institution-admin/users/u-a1/roles").param("institution", A).with(TestCallers.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"super_admin\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aGrantHolderWhoAlsoHoldsABroadRoleIsRefusedEverywhere() throws Exception {
        var caller = TestCallers.caller("minagri.admin", Set.of("inst:minagri:admin"),
                Map.of("realm-management", Set.of("manage-users")), A);
        mvc.perform(get("/institution-admin/users").with(TestCallers.as(caller)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("guardrail_violation"));
        verify(kc, never()).groupMembers(any(), anyString());
    }

    @Test
    void aContradictingInstitutionClaimIsRefused() throws Exception {
        var caller = TestCallers.caller("minagri.admin", Set.of("inst:minagri:admin"), B);
        mvc.perform(get("/institution-admin/me").with(TestCallers.as(caller)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("institution_claim_mismatch"));
    }
}
