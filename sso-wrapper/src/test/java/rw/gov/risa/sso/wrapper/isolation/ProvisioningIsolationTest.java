package rw.gov.risa.sso.wrapper.isolation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.service.clientaccess.AccessPolicyStore;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.service.provisioning.EmployeeMirror;
import rw.gov.risa.sso.wrapper.service.provisioning.ProvisioningFixtures;
import rw.gov.risa.sso.wrapper.support.TestCallers;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/**
 * Spec §7.1 (the institution boundary held by the wrapper) for {@code POST /institution-admin/employees/provision}:
 * the REAL provisioning service, institution resolution and client-scope rules, with only the lowest Keycloak
 * and mirror layers mocked. A minagri admin can never reach a minisante client or employee, and naming
 * another institution is refused.
 */
@WrapperTest
class ProvisioningIsolationTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuditEmitter audit;
    @MockitoBean KeycloakAdminFactory factory;
    @MockitoBean KeycloakExecutors executors;
    @MockitoBean KeycloakDirectory directory;
    @MockitoBean AccessPolicyStore policies;
    @MockitoBean EmployeeMirror mirror;

    RealmResource realm = mock(RealmResource.class);

    static String body(String employee, String client) {
        return "{\"employee_numbers\":[\"" + employee + "\"],\"client_ids\":[\"" + client + "\"],\"dry_run\":false}";
    }

    @BeforeEach
    void setUp() {
        KeycloakExec exec = KeycloakExec.of(realm);
        when(executors.serviceAccount()).thenReturn(exec);
        when(executors.asCaller(anyString())).thenReturn(exec);
        when(directory.listClients(any())).thenReturn(List.of(
                ProvisioningFixtures.client("minagri-kubaka-web", "email"),
                ProvisioningFixtures.client("minisante-hmis", "email")));
    }

    @Test
    void anotherInstitutionsClientRefusesTheWholeRequest() throws Exception {
        mvc.perform(post("/institution-admin/employees/provision").with(TestCallers.institutionAdmin("minagri", "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("E1001", "minisante-hmis")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("client_out_of_scope"));
        verify(mirror, never()).records(anyString(), anyList());
        verify(policies, never()).apply(any(), anyString(), anyList());
    }

    @Test
    void theOtherWayRoundToo() throws Exception {
        mvc.perform(post("/institution-admin/employees/provision")
                        .with(TestCallers.institutionAdmin("minisante", "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("MS1", "minagri-kubaka-web")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("client_out_of_scope"));
    }

    @Test
    void anotherInstitutionsEmployeeIsNotFoundBecauseTheMirrorIsAlwaysReadForTheCallersInstitution()
            throws Exception {
        when(mirror.records(eq("minagri"), anyList())).thenReturn(List.of());
        mvc.perform(post("/institution-admin/employees/provision").with(TestCallers.institutionAdmin("minagri", "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("MS1", "minagri-kubaka-web")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employees[0].reason").value("not_found"))
                .andExpect(jsonPath("$.employees[0].account").value("not_created"))
                .andExpect(jsonPath("$.totals.grants").value(0));
        verify(mirror).records(eq("minagri"), eq(List.of("MS1")));
        verify(mirror, never()).records(eq("minisante"), anyList());
        verify(policies, never()).apply(any(), anyString(), anyList());
    }

    @Test
    void namingAnotherInstitutionIsRefusedNeverHonoured() throws Exception {
        mvc.perform(post("/institution-admin/employees/provision").param("institution", "minisante")
                        .with(TestCallers.institutionAdmin("minagri", "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("MS1", "minisante-hmis")))
                .andExpect(status().isForbidden());
        verify(mirror, never()).records(anyString(), anyList());
    }
}
