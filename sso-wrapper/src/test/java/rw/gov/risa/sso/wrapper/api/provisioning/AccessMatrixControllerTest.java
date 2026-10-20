package rw.gov.risa.sso.wrapper.api.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.BulkItem;
import rw.gov.risa.sso.wrapper.service.accessmatrix.AccessMatrixService;
import rw.gov.risa.sso.wrapper.service.accessmatrix.AccessMatrixTransferService;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews;
import rw.gov.risa.sso.wrapper.support.TestCallers;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/** Every access-matrix route: capability gates, request mapping and wire shape (services mocked). */
@WrapperTest
class AccessMatrixControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuditEmitter audit;
    @MockitoBean KeycloakAdminFactory keycloak;
    @MockitoBean AccessMatrixService matrix;
    @MockitoBean AccessMatrixTransferService transfer;

    static MatrixViews.BulkReport report() {
        return new MatrixViews.BulkReport("minagri", "grant", "ui", "b1",
                List.of(new MatrixViews.BulkItemResult("E1001", "u-a", "minagri-kubaka-web", "applied", null, null,
                        false, null, null, "sent")),
                Map.of("applied", 1, "rejected", 0, "failed", 0), Map.of("sent", 1));
    }

    @Test
    void pageNeedsViewClientsAndIsScopedToTheCallersInstitution() throws Exception {
        when(matrix.matrix(any(), any(), anyList(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MatrixViews.AccessMatrixPage("minagri", List.of(), List.of(), 1, 50, 0, 0, Map.of()));
        mvc.perform(get("/institution-admin/access-matrix").param("clients", "minagri-a, minagri-b")
                        .param("page_size", "20").param("access", "has")
                        .with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institution").value("minagri"))
                .andExpect(jsonPath("$.page_size").value(50))
                .andExpect(jsonPath("$.total_employees").value(0));
        verify(matrix).matrix(org.mockito.ArgumentMatchers.argThat(c -> c.institution().equals("minagri")), any(),
                eq(List.of("minagri-a", "minagri-b")), eq(1), eq(20), isNull(), eq("has"), isNull(), isNull());

        // hr holds no view-clients
        mvc.perform(get("/institution-admin/access-matrix").param("clients", "minagri-a")
                        .with(TestCallers.institutionAdmin("minagri", "hr")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("capability_denied"));
        mvc.perform(get("/institution-admin/access-matrix").param("clients", "minagri-a"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bulkMapsItemsKeyedByEmployeeNumber() throws Exception {
        when(matrix.bulk(any(), any(), any())).thenReturn(report());
        mvc.perform(post("/institution-admin/grants/bulk").with(TestCallers.institutionAdmin("minagri", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"grant\",\"items\":[{\"employeeNumber\":\"E1001\","
                                + "\"clientId\":\"minagri-kubaka-web\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch_id").value("b1"))
                .andExpect(jsonPath("$.results[0].employee_number").value("E1001"))
                .andExpect(jsonPath("$.results[0].notified").value("sent"))
                .andExpect(jsonPath("$.summary.applied").value(1));
        ArgumentCaptor<AccessMatrixService.BulkCommand> cmd = ArgumentCaptor.forClass(AccessMatrixService.BulkCommand.class);
        verify(matrix).bulk(any(), any(), cmd.capture());
        assertThat(cmd.getValue().items()).containsExactly(new BulkItem("E1001", "minagri-kubaka-web"));
        assertThat(cmd.getValue().channel()).isEqualTo("ui");
        assertThat(cmd.getValue().batchId()).isNull();
    }

    @Test
    void bulkRefusesTheProvisioningChannelAndTiersWithoutManageClients() throws Exception {
        mvc.perform(post("/institution-admin/grants/bulk").with(TestCallers.institutionAdmin("minagri", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"grant\",\"channel\":\"auto_provisioning\",\"items\":[{\"employeeNumber\""
                                + ":\"E1\",\"clientId\":\"minagri-a\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_channel"));
        mvc.perform(post("/institution-admin/grants/bulk").with(TestCallers.institutionAdmin("minagri", "auditor"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"grant\",\"items\":[{\"employeeNumber\":\"E1\",\"clientId\":\"minagri-a\"}]}"))
                .andExpect(status().isForbidden());
        verify(matrix, never()).bulk(any(), any(), any());
    }

    @Test
    void nidRevealAndSetupEmail() throws Exception {
        when(matrix.revealNid(any(), any(), eq("E1001"), any())).thenReturn(new MatrixViews.RevealedNid("E1001", "1198"));
        mvc.perform(get("/institution-admin/access-matrix/nid/E1001").with(TestCallers.institutionAdmin("minagri", "auditor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nid").value("1198"));

        when(matrix.resendSetupEmail(any(), any(), eq("E1001"), any()))
                .thenReturn(new MatrixViews.SetupEmailResend("E1001", "u-a", "sent", "ok"));
        mvc.perform(post("/institution-admin/access-matrix/setup-email/E1001")
                        .with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("sent"));
        // auditor: no manage-members
        mvc.perform(post("/institution-admin/access-matrix/setup-email/E1001")
                        .with(TestCallers.institutionAdmin("minagri", "auditor")))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportIsAnXlsxDownloadThatIsNeverCached() throws Exception {
        when(transfer.export(any(), any(), eq(List.of("minagri-a")), any()))
                .thenReturn(new AccessMatrixTransferService.Export(new byte[] {1, 2, 3},
                        "access-matrix-minagri-2026-10-20.xlsx", "x"));
        mvc.perform(get("/institution-admin/access-matrix/export").param("clients", "minagri-a")
                        .with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", AccessMatrixController.XLSX))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"access-matrix-minagri-2026-10-20.xlsx\""))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void importPreviewAndApply() throws Exception {
        when(transfer.preview(any(), any(), any(), anyString(), anyString())).thenReturn(new MatrixViews.ImportPreviewView(
                "imp1", "2026-10-20T08:30:00+00:00", "minagri", "Jane", "m.xlsx", "sha", "e", "t", "a",
                List.of("minagri-a"), Map.of("will_grant", 1), List.of()));
        MockMultipartFile file = new MockMultipartFile("file", "m.xlsx", AccessMatrixController.XLSX, new byte[] {9, 9});
        mvc.perform(multipart("/institution-admin/access-matrix/import").file(file).param("approved_by", "Jane")
                        .with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.import_id").value("imp1"));
        ArgumentCaptor<byte[]> data = ArgumentCaptor.forClass(byte[].class);
        verify(transfer).preview(any(), any(), data.capture(), eq("m.xlsx"), eq("Jane"));
        assertThat(data.getValue()).containsExactly(9, 9);

        when(transfer.apply(any(), any(), eq("imp1"), any())).thenReturn(new MatrixViews.ImportReport("minagri",
                "grant", "bulk", "imp1", List.of(), Map.of(), Map.of(), "imp1", "Jane", "m.xlsx"));
        mvc.perform(post("/institution-admin/access-matrix/import/imp1/apply")
                        .with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.batch_id").value("imp1"))
                .andExpect(jsonPath("$.approved_by").value("Jane"));
        // auditor may not import
        mvc.perform(multipart("/institution-admin/access-matrix/import").file(file).param("approved_by", "Jane")
                .with(TestCallers.institutionAdmin("minagri", "auditor"))).andExpect(status().isForbidden());
    }
}
