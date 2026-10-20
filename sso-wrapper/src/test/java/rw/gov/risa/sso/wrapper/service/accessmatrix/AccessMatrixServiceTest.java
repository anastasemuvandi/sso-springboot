package rw.gov.risa.sso.wrapper.service.accessmatrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.BulkItem;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Holder;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.AdmitManyResult;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.RevokeResult;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.AccountStatus;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.BatchTotals;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.Outcome;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.PlanAction;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ProvisionReport;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ReportRow;
import rw.gov.risa.sso.wrapper.policy.setuplink.Delivery;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.AccessMatrixPage;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.BulkItemResult;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.BulkReport;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.MatrixRow;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;
import rw.gov.risa.sso.wrapper.service.provisioning.EmployeeProvisioningService;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService;

/** Spec §5.14: masked NIDs, locked rows, the concurrency guard, the whole-request 403, one email per person. */
class AccessMatrixServiceTest {

    MatrixFixture f = new MatrixFixture();

    static MatrixRow row(AccessMatrixPage p, String number) {
        return p.items().stream().filter(r -> r.employeeNumber().equals(number)).findFirst().orElseThrow();
    }

    static BulkItemResult result(BulkReport r, String number, String client) {
        return r.results().stream().filter(x -> x.employeeNumber().equals(number) && x.clientId().equals(client))
                .findFirst().orElseThrow();
    }

    BulkReport bulk(String action, BulkItem... items) {
        return f.service.bulk(f.ctx, f.caller, new AccessMatrixService.BulkCommand(action, List.of(items), "ui",
                "1.2.3.4", null, null));
    }

    List<AuditEvent> audited() {
        ArgumentCaptor<AuditEvent.Builder> c = ArgumentCaptor.forClass(AuditEvent.Builder.class);
        verify(f.audit, org.mockito.Mockito.atLeast(0)).emit(c.capture());
        return c.getAllValues().stream().map(AuditEvent.Builder::build).toList();
    }

    // ---- the page --------------------------------------------------------------------------

    @Test
    void rowsAreTheMirrorsEmployeesWithMaskedNidsAndLockedRows() throws Exception {
        AccessMatrixPage p = f.service.matrix(f.ctx, f.caller, List.of("minagri-a", "minagri-ro"), 1, 50, null, null,
                null, null);

        assertThat(p.totalEmployees()).isEqualTo(6);
        assertThat(p.items()).extracting(MatrixRow::employeeNumber).contains("E1002"); // no account, still a row
        MatrixRow alice = row(p, "E1001");
        assertThat(alice.nidMasked()).isEqualTo("••••••••••••5678");
        assertThat(alice.hasNid()).isTrue();
        assertThat(alice.cells().get("minagri-a").hasAccess()).isTrue();
        assertThat(alice.account()).isEqualTo("existing");
        assertThat(row(p, "E1002").account()).isEqualTo("none");
        assertThat(row(p, "E1003").status()).isEqualTo("terminated");
        assertThat(row(p, "E1004").account()).isEqualTo("elsewhere");
        assertThat(row(p, "E1004").lockedReason()).isNotBlank();
        assertThat(row(p, "E1005").account()).isEqualTo("ambiguous");
        assertThat(row(p, "E1006").lockedReason()).isNotBlank();
        assertThat(p.columns()).extracting(c -> c.clientId() + ":" + c.editable())
                .containsExactly("minagri-a:true", "minagri-ro:false");
        assertThat(p.counts()).containsEntry("minagri-a", 1).containsEntry("minagri-ro", 0);
        // the full NID never leaves in the page
        assertThat(new ObjectMapper().writeValueAsString(p)).doesNotContain(MatrixFixture.NID);
    }

    @Test
    void filtersSearchAndPaging() {
        AccessMatrixPage has = f.service.matrix(f.ctx, f.caller, List.of("minagri-a"), 1, 50, null, "has", null, null);
        assertThat(has.items()).extracting(MatrixRow::employeeNumber).containsExactly("E1001");
        AccessMatrixPage byNid = f.service.matrix(f.ctx, f.caller, List.of("minagri-a"), 1, 50, "5678", null, null,
                null);
        assertThat(byNid.items()).extracting(MatrixRow::employeeNumber).containsExactly("E1001");
        AccessMatrixPage page2 = f.service.matrix(f.ctx, f.caller, List.of("minagri-a"), 2, 4, null, null, null, null);
        assertThat(page2.items()).hasSize(2);
        assertThat(page2.page()).isEqualTo(2);
        assertThatThrownBy(() -> f.service.matrix(f.ctx, f.caller, List.of("minagri-a"), 1, 50, null, "has",
                "minagri-b", null)).isInstanceOf(ApiException.class);
    }

    @Test
    void awaitingPasswordRowsOfferTheResendOnlyAfterAFailedDelivery() {
        f.members.get("u-a").setRequiredActions(List.of("UPDATE_PASSWORD"));
        when(f.setup.lastDelivery("u-a")).thenReturn(Optional.of(new Delivery("failed", "provisioning", "t")));
        MatrixRow alice = row(f.service.matrix(f.ctx, f.caller, List.of("minagri-a"), 1, 50, null, null, null, null),
                "E1001");
        assertThat(alice.awaitingPassword()).isTrue();
        assertThat(alice.setupEmail().status()).isEqualTo("failed");
        assertThat(alice.setupEmailResendable()).isTrue();

        when(f.setup.lastDelivery("u-a")).thenReturn(Optional.of(new Delivery("sent", "provisioning", "t")));
        alice = row(f.service.matrix(f.ctx, f.caller, List.of("minagri-a"), 1, 50, null, null, null, null), "E1001");
        assertThat(alice.setupEmailResendable()).isFalse();
    }

    @Test
    void aColumnOutsideTheInstitutionIs403() {
        assertThatThrownBy(() -> f.service.matrix(f.ctx, f.caller, List.of("minagri-a", "minisante-hmis"), 1, 50, null,
                null, null, null)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));
    }

    // ---- NID reveal ------------------------------------------------------------------------

    @Test
    void theFullNidIsAuditedOnEveryCall() {
        assertThat(f.service.revealNid(f.ctx, f.caller, "E1001", "1.2.3.4").nid()).isEqualTo(MatrixFixture.NID);
        f.service.revealNid(f.ctx, f.caller, "E1001", "1.2.3.4");
        List<AuditEvent> events = audited();
        assertThat(events).hasSize(2).allSatisfy(e -> {
            assertThat(e.type()).isEqualTo(AuditEventType.EMPLOYEE_NID_REVEALED);
            assertThat(e.actor()).isEqualTo("minagri.admin");
            assertThat(e.extra()).containsEntry("employee_number", "E1001").containsEntry("institution", "minagri")
                    .doesNotContainValue(MatrixFixture.NID);
        });
        assertThatThrownBy(() -> f.service.revealNid(f.ctx, f.caller, "MINISANTE-1", null))
                .isInstanceOf(ApiException.class).satisfies(e ->
                        assertThat(((ApiException) e).status().value()).isEqualTo(404));
    }

    // ---- setup-email resend ----------------------------------------------------------------

    @Test
    void setupEmailResend() {
        when(f.setup.adminResend("u-a", "minagri.admin", "minagri", null))
                .thenReturn(new SetupLinkService.AdminResend("sent", null, null));
        assertThat(f.service.resendSetupEmail(f.ctx, f.caller, "E1001", null).status()).isEqualTo("sent");

        when(f.setup.adminResend("u-a", "minagri.admin", "minagri", null))
                .thenReturn(new SetupLinkService.AdminResend("last_email_delivered", "x", null));
        assertThatThrownBy(() -> f.service.resendSetupEmail(f.ctx, f.caller, "E1001", null))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
        assertThatThrownBy(() -> f.service.resendSetupEmail(f.ctx, f.caller, "E1002", null))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
        assertThatThrownBy(() -> f.service.resendSetupEmail(f.ctx, f.caller, "E9999", null))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));
    }

    // ---- bulk: the concurrency guard --------------------------------------------------------

    @Test
    void grantOnlyAddsAndRevokeOnlyRemoves() {
        when(f.kc.admit(any(), eq("minagri-b"), anyBoolean(), any()))
                .thenReturn(new AdmitManyResult(ClientAssignment.EMPTY, List.of("u-a"), List.of(), List.of()));

        BulkReport grant = bulk("grant", new BulkItem("E1001", "minagri-a"), new BulkItem("E1001", "minagri-b"));
        // already has minagri-a → state_changed, never written
        assertThat(result(grant, "E1001", "minagri-a").status()).isEqualTo("rejected");
        assertThat(result(grant, "E1001", "minagri-a").reason()).isEqualTo("state_changed");
        assertThat(result(grant, "E1001", "minagri-b").status()).isEqualTo("applied");
        verify(f.kc, never()).admit(any(), eq("minagri-a"), anyBoolean(), any());

        BulkReport revoke = bulk("revoke", new BulkItem("E1001", "minagri-b"));
        // the page said "has access", but it no longer has → state_changed, nothing written
        assertThat(result(revoke, "E1001", "minagri-b").reason()).isEqualTo("state_changed");
        verify(f.kc, never()).revoke(any(), anyString(), anyBoolean(), any());
    }

    @Test
    void aCellThatChangedDuringTheWriteIsStateChanged() {
        f.holders.get("minagri-a").clear();
        f.holders.get("minagri-a").put("u-c", new Holder(true, List.of(), List.of(), false));
        // The policy write admitted nobody new and reports nobody as already there.
        when(f.kc.admit(any(), eq("minagri-a"), anyBoolean(), any()))
                .thenReturn(new AdmitManyResult(ClientAssignment.EMPTY, List.of(), List.of(), List.of()));
        BulkReport r = bulk("grant", new BulkItem("E1001", "minagri-a"));
        assertThat(result(r, "E1001", "minagri-a").reason()).isEqualTo("state_changed");
        assertThat(audited()).noneMatch(e -> e.type() == AuditEventType.ADMIN_CLIENT_UPDATED);
    }

    @Test
    void revokeAppliesAndIsAuditedPerUserClientAndToldOnce() {
        when(f.kc.revoke(any(), eq("minagri-a"), anyBoolean(), any()))
                .thenReturn(new RevokeResult(ClientAssignment.EMPTY, List.of("u-a"), List.of(), List.of(), List.of()));
        BulkReport r = bulk("revoke", new BulkItem("E1001", "minagri-a"));
        assertThat(result(r, "E1001", "minagri-a").status()).isEqualTo("applied");
        assertThat(result(r, "E1001", "minagri-a").notified()).isEqualTo("sent");
        AuditEvent e = audited().get(0);
        assertThat(e.type()).isEqualTo(AuditEventType.ADMIN_CLIENT_UPDATED);
        assertThat(e.extra()).containsEntry("action", "revoke_user_access").containsEntry("target_user", "u-a")
                .containsEntry("target_client", "minagri-a").containsEntry("batch_id", r.batchId())
                .containsEntry("unassigned", true);
        verify(f.mailer).sendAccessRevoked(any());
    }

    @Test
    void lockedRowsLeaversAndReadOnlyColumnsAreRejectedPerItem() {
        BulkReport r = bulk("grant", new BulkItem("E1003", "minagri-b"), new BulkItem("E1004", "minagri-b"),
                new BulkItem("E1005", "minagri-b"), new BulkItem("E1001", "minagri-ro"),
                new BulkItem("E1001", "minagri-ro"));
        assertThat(result(r, "E1003", "minagri-b").reason()).isEqualTo("employee_left");
        assertThat(result(r, "E1004", "minagri-b").reason()).isEqualTo("account_unresolved");
        assertThat(result(r, "E1005", "minagri-b").reason()).isEqualTo("account_unresolved");
        assertThat(r.results()).filteredOn(x -> "minagri-ro".equals(x.clientId())).extracting(BulkItemResult::reason)
                .containsExactlyInAnyOrder("duplicate", "not_manageable");
        verify(f.kc, never()).admit(any(), anyString(), anyBoolean(), any());
        assertThat(r.summary()).containsEntry("rejected", 5).containsEntry("applied", 0);
    }

    // ---- bulk: the whole-request 403 --------------------------------------------------------

    @Test
    void anyItemOutsideTheInstitutionRefusesTheWholeRequest() {
        assertThatThrownBy(() -> bulk("grant", new BulkItem("E1001", "minagri-b"), new BulkItem("MS-1", "minagri-b")))
                .isInstanceOf(ApiException.class).satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(403);
                    assertThat(((ApiException) e).error()).isEqualTo("employee_outside_institution");
                });
        assertThatThrownBy(() -> bulk("grant", new BulkItem("E1001", "minisante-hmis")))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));
        verify(f.kc, never()).admit(any(), anyString(), anyBoolean(), any());
        verify(f.provisioning, never()).run(any(), any());
    }

    @Test
    void requestLevelRefusals() {
        assertThatThrownBy(() -> bulk("delete", new BulkItem("E1001", "minagri-b")))
                .satisfies(e -> assertThat(((ApiException) e).error()).isEqualTo("invalid_action"));
        assertThatThrownBy(() -> bulk("grant")).satisfies(e -> assertThat(((ApiException) e).error())
                .isEqualTo("no_items"));
    }

    // ---- bulk: no account → provisioning under the batch id --------------------------------

    @Test
    void aGrantToSomeoneWithNoAccountGoesThroughProvisioningUnderTheBatchId() {
        ReportRow created = new ReportRow("E1002", "Bob Habimana", "bob@minagri.gov.rw", PlanAction.CREATE,
                AccountStatus.CREATED, null, "", List.of("minagri-b"), List.of(), List.of(), Outcome.CREATED, "u-new",
                Map.of("password_email", "failed: relay down"));
        when(f.provisioning.run(eq(f.ctx), any())).thenReturn(new ProvisionReport("minagri", false, List.of(),
                new BatchTotals(1, 1, 0, 0, 1, 0, 0, 0, 0, List.of()), List.of(created)));

        BulkReport r = bulk("grant", new BulkItem("E1002", "minagri-b"));

        ArgumentCaptor<EmployeeProvisioningService.Command> cmd =
                ArgumentCaptor.forClass(EmployeeProvisioningService.Command.class);
        verify(f.provisioning).run(eq(f.ctx), cmd.capture());
        assertThat(cmd.getValue().batchId()).isEqualTo(r.batchId());
        assertThat(cmd.getValue().channel().code()).isEqualTo("ui");
        assertThat(cmd.getValue().employeeNumbers()).containsExactly("E1002");
        assertThat(cmd.getValue().dryRun()).isFalse();
        BulkItemResult item = result(r, "E1002", "minagri-b");
        assertThat(item.status()).isEqualTo("applied");
        assertThat(item.accountCreated()).isTrue();
        assertThat(item.sub()).isEqualTo("u-new");
        assertThat(item.setupEmail()).isEqualTo("failed");
        assertThat(item.setupEmailError()).isEqualTo("relay down");
        assertThat(item.notified()).isNull(); // a created account is told by provisioning's own email
        verify(f.mailer, never()).sendAccessGranted(any());
    }

    // ---- one email per person per request ---------------------------------------------------

    @Test
    void onePersonGrantedTwoAppsGetsOneEmail() {
        f.holders.get("minagri-a").clear();
        when(f.kc.admit(any(), anyString(), anyBoolean(), any()))
                .thenReturn(new AdmitManyResult(ClientAssignment.EMPTY, List.of("u-a"), List.of(), List.of()));

        BulkReport r = bulk("grant", new BulkItem("E1001", "minagri-a"), new BulkItem("E1001", "minagri-b"));

        assertThat(r.summary()).containsEntry("applied", 2);
        ArgumentCaptor<Mailer.AccessChangeMail> m = ArgumentCaptor.forClass(Mailer.AccessChangeMail.class);
        verify(f.mailer, times(1)).sendAccessGranted(m.capture());
        assertThat(m.getValue().applications()).hasSize(2);
        assertThat(r.notifications()).containsEntry("sent", 1);
        assertThat(audited()).filteredOn(e -> e.type() == AuditEventType.ADMIN_CLIENT_UPDATED).hasSize(2);
    }
}
