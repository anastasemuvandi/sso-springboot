package rw.gov.risa.sso.wrapper.api.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.AccountStatus;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.BatchTotals;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.Outcome;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.PlanAction;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ProvisionReport;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ReportRow;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.service.provisioning.EmployeeProvisioningService;
import rw.gov.risa.sso.wrapper.support.TestCallers;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/** {@code POST /institution-admin/employees/provision}: gate, scope, wire shape. */
@WrapperTest
class ProvisioningControllerTest {

    static final String BODY = "{\"employee_numbers\":[\"E1001\"],\"client_ids\":[\"minagri-kubaka-web\"],"
            + "\"dry_run\":true}";

    @Autowired MockMvc mvc;
    @MockitoBean AuditEmitter audit;
    @MockitoBean KeycloakAdminFactory keycloak;
    @MockitoBean EmployeeProvisioningService provisioning;

    void reportOk() {
        ReportRow row = new ReportRow("E1001", "Alice", "alice@minagri.gov.rw", PlanAction.CREATE,
                AccountStatus.CREATED, null, "Alice is given 1 of 1", List.of("minagri-kubaka-web"), List.of(),
                List.of(), Outcome.CREATED, null, Map.of());
        when(provisioning.run(any(), any())).thenReturn(new ProvisionReport("minagri", true, List.of(),
                new BatchTotals(1, 1, 0, 0, 1, 0, 0, 0, 0, List.of()), List.of(row)));
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(post("/institution-admin/employees/provision").contentType(MediaType.APPLICATION_JSON)
                .content(BODY)).andExpect(status().isUnauthorized());
    }

    @Test
    void institutionAdminProvisionsInTheirOwnInstitution() throws Exception {
        reportOk();
        mvc.perform(post("/institution-admin/employees/provision").with(TestCallers.institutionAdmin("minagri", "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institution").value("minagri"))
                .andExpect(jsonPath("$.dry_run").value(true))
                .andExpect(jsonPath("$.totals.created").value(1))
                .andExpect(jsonPath("$.employees[0].account").value("created"))
                .andExpect(jsonPath("$.employees[0].clients_granted[0]").value("minagri-kubaka-web"))
                .andExpect(jsonPath("$.employees[0].clients_skipped").isArray())
                .andExpect(jsonPath("$.employees[0].clients_denied").isArray());
        ArgumentCaptor<InstitutionContext> ctx = ArgumentCaptor.forClass(InstitutionContext.class);
        ArgumentCaptor<EmployeeProvisioningService.Command> cmd =
                ArgumentCaptor.forClass(EmployeeProvisioningService.Command.class);
        verify(provisioning).run(ctx.capture(), cmd.capture());
        assertThat(ctx.getValue().institution()).isEqualTo("minagri");
        assertThat(ctx.getValue().actor()).isEqualTo("minagri.admin");
        assertThat(cmd.getValue().employeeNumbers()).containsExactly("E1001");
        assertThat(cmd.getValue().dryRun()).isTrue();
    }

    @Test
    void aTierWithoutManageMembersIsRefused() throws Exception {
        mvc.perform(post("/institution-admin/employees/provision")
                        .with(TestCallers.institutionAdmin("minagri", "auditor"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("capability_denied"));
        verify(provisioning, never()).run(any(), any());
    }

    @Test
    void aPlainUserIsRefused() throws Exception {
        mvc.perform(post("/institution-admin/employees/provision").with(TestCallers.plainUser())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verify(provisioning, never()).run(any(), any());
    }

    @Test
    void theReportNeverCarriesAPasswordValue() throws Exception {
        ReportRow row = new ReportRow("E1001", "Alice", "alice@minagri.gov.rw", PlanAction.CREATE,
                AccountStatus.CREATED, null, "", List.of("minagri-kubaka-web"), List.of(), List.of(), Outcome.CREATED,
                "u-1", Map.of("password", "set_by_holder", "password_email", "sent", "welcome_email", "sent"));
        when(provisioning.run(any(), any())).thenReturn(new ProvisionReport("minagri", false, List.of(),
                new BatchTotals(1, 1, 0, 0, 1, 0, 0, 0, 0, List.of()), List.of(row)));
        mvc.perform(post("/institution-admin/employees/provision").with(TestCallers.institutionAdmin("minagri", "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("true", "false")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employees[0].password").value("set_by_holder"))
                .andExpect(jsonPath("$.employees[0].password_email").value("sent"))
                .andExpect(jsonPath("$.employees[0].user_id").value("u-1"));
    }
}
