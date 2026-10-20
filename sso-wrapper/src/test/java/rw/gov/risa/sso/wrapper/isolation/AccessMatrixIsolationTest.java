package rw.gov.risa.sso.wrapper.isolation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountState;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RosterEmployee;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixWorkbook;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.SheetColumn;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.SheetMeta;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.SheetRow;
import rw.gov.risa.sso.wrapper.service.accessmatrix.ImportPlanStore;
import rw.gov.risa.sso.wrapper.service.clientaccess.AccessPolicyStore;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.service.provisioning.EmployeeMirror;
import rw.gov.risa.sso.wrapper.service.provisioning.ProvisioningFixtures;
import rw.gov.risa.sso.wrapper.support.TestCallers;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/**
 * Spec §7.1 for the access matrix: real services and institution resolution, only Keycloak's lowest layer,
 * the mirror and the plan store mocked. A minagri admin cannot read, reveal, grant, revoke, export or import
 * anything of minisante's — refused whole, with nothing written.
 */
@WrapperTest
@TestPropertySource(properties = "sso.access-matrix.signing-key=isolation-test-key")
class AccessMatrixIsolationTest {

    static final String KEY = "isolation-test-key";

    @Autowired MockMvc mvc;
    @MockitoBean AuditEmitter audit;
    @MockitoBean KeycloakAdminFactory factory;
    @MockitoBean KeycloakExecutors executors;
    @MockitoBean KeycloakDirectory directory;
    @MockitoBean AccessPolicyStore policies;
    @MockitoBean EmployeeMirror mirror;
    @MockitoBean ImportPlanStore plans;

    RealmResource realm = mock(RealmResource.class);

    @BeforeEach
    void setUp() {
        KeycloakExec exec = KeycloakExec.of(realm);
        when(executors.serviceAccount()).thenReturn(exec);
        when(executors.asCaller(anyString())).thenReturn(exec);
        when(directory.listClients(any())).thenReturn(List.of(
                ProvisioningFixtures.client("minagri-kubaka-web", "email"),
                ProvisioningFixtures.client("minisante-hmis", "email")));
        GroupRepresentation g = new GroupRepresentation();
        g.setId("gid-minagri");
        g.setName("minagri");
        when(directory.groupByPath(any(), org.mockito.ArgumentMatchers.eq("/minagri"))).thenReturn(Optional.of(g));
        when(directory.groupTreeIds(any(), org.mockito.ArgumentMatchers.eq("gid-minagri")))
                .thenReturn(List.of("gid-minagri"));
        when(mirror.roster("minagri")).thenReturn(List.of(new RosterEmployee("E1001", "Alice", "Uwase",
                "alice@minagri.gov.rw", "1198780012345678", "active", "F", null, "minagri")));
        when(mirror.roster("minisante")).thenReturn(List.of(new RosterEmployee("MS1", "Marie", "Mukamana",
                "marie@minisante.gov.rw", "1199080012345678", "active", "F", null, "minisante")));
    }

    void nothingWritten() {
        verify(policies, never()).apply(any(), anyString(), anyList());
    }

    @Test
    void aBulkItemNamingAnotherInstitutionsEmployeeRefusesTheWholeRequest() throws Exception {
        mvc.perform(post("/institution-admin/grants/bulk").with(TestCallers.institutionAdmin("minagri", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"grant\",\"items\":[{\"employeeNumber\":\"E1001\",\"clientId\":"
                                + "\"minagri-kubaka-web\"},{\"employeeNumber\":\"MS1\",\"clientId\":\"minagri-kubaka-web\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("employee_outside_institution"));
        nothingWritten();
        verify(mirror, never()).roster("minisante");
    }

    @Test
    void aBulkItemNamingAnotherInstitutionsClientRefusesTheWholeRequest() throws Exception {
        mvc.perform(post("/institution-admin/grants/bulk").with(TestCallers.institutionAdmin("minagri", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"revoke\",\"items\":[{\"employeeNumber\":\"E1001\",\"clientId\":"
                                + "\"minisante-hmis\"}]}"))
                .andExpect(status().isForbidden());
        nothingWritten();
    }

    @Test
    void theMatrixOfAnotherInstitutionsAppIsRefused() throws Exception {
        mvc.perform(get("/institution-admin/access-matrix").param("clients", "minisante-hmis")
                        .with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/institution-admin/access-matrix/export").param("clients", "minisante-hmis")
                        .with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        verify(audit, never()).emit(argThat((AuditEvent.Builder b) ->
                b.build().type() == AuditEventType.ACCESS_MATRIX_EXPORTED));
    }

    @Test
    void namingAnotherInstitutionIsRefused() throws Exception {
        mvc.perform(get("/institution-admin/access-matrix").param("clients", "minisante-hmis")
                        .param("institution", "minisante").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        verify(mirror, never()).roster("minisante");
    }

    @Test
    void anotherInstitutionsEmployeesNidAndSetupEmailAre404() throws Exception {
        mvc.perform(get("/institution-admin/access-matrix/nid/MS1").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/institution-admin/access-matrix/setup-email/MS1")
                        .with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isNotFound());
        verify(audit, never()).emit(argThat((AuditEvent.Builder b) ->
                b.build().type() == AuditEventType.EMPLOYEE_NID_REVEALED));
        verify(mirror, never()).roster("minisante");
    }

    @Test
    void anotherInstitutionsSignedFileIsRefused() throws Exception {
        byte[] theirs = AccessMatrixWorkbook.build(new SheetMeta("minisante", List.of("minisante-hmis"), "x",
                        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0).toString(), "minisante.admin"),
                List.of(new SheetColumn("minisante-hmis", "HMIS", true)),
                List.of(new SheetRow("MS1", "Marie", "M", null, "active", AccountState.NONE, true,
                        Map.of("minisante-hmis", true))), KEY);
        mvc.perform(multipart("/institution-admin/access-matrix/import")
                        .file(new MockMultipartFile("file", "x.xlsx", "application/octet-stream", theirs))
                        .param("approved_by", "Jane Director").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("other_institution_file"));
        verify(plans, never()).save(anyString(), any());
    }

    @Test
    void anImportPreviewedForAnotherInstitutionCannotBeApplied() throws Exception {
        when(plans.get("imp-ms")).thenReturn(Optional.of(new ImportPlanStore.Plan("minisante", "minisante.admin",
                "Jane", null, "sha", "e", "t", "minisante.admin", List.of("minisante-hmis"),
                List.of(new ImportPlanStore.PlannedItem("MS1", "minisante-hmis")))));
        mvc.perform(post("/institution-admin/access-matrix/import/imp-ms/apply")
                        .with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        verify(plans, never()).consume(anyString());
        nothingWritten();
    }
}
