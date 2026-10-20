package rw.gov.risa.sso.wrapper.service.accessmatrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountState;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Holder;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixSheet;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixWorkbook;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.SheetColumn;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.SheetMeta;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.SheetRow;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.AdmitManyResult;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.BatchTotals;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ClientDecision;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.DenyReason;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.Outcome;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.PlanAction;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ProvisionReport;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ReportRow;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.AccountStatus;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumn;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.ImportItem;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.ImportPreviewView;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.ImportReport;
import rw.gov.risa.sso.wrapper.service.provisioning.EmployeeProvisioningService;
import rw.gov.risa.sso.wrapper.service.provisioning.ProvisioningFixtures;

/** Spec §5.14 / §7.6: the Excel round-trip applies only the server-side plan, re-checks, never revokes. */
class AccessMatrixTransferServiceTest {

    static final String KEY = "test-signing-key";
    static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");

    MatrixFixture f = new MatrixFixture();
    Map<String, String> redisData = new HashMap<>();
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    ImportPlanStore plans = new ImportPlanStore(redis);
    AccessMatrixTransferService transfer = new AccessMatrixTransferService(f.service, f.provisioning, plans,
            new AccessMatrixProperties(KEY), f.audit, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.doAnswer(i -> redisData.put(i.getArgument(0), i.getArgument(1))).when(ops)
                .set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(i -> redisData.get(i.<String>getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(i -> redisData.remove(i.<String>getArgument(0)) != null);
        when(f.kc.admit(any(), anyString(), anyBoolean(), any())).thenAnswer(i ->
                new AdmitManyResult(ClientAssignment.EMPTY, List.copyOf(i.<java.util.Collection<String>>getArgument(3)),
                        List.of(), List.of()));
    }

    byte[] export() {
        return transfer.export(f.ctx, f.caller, List.of("minagri-a", "minagri-b"), "1.2.3.4").data();
    }

    /** Set (YES) or clear a cell of the Access sheet, by employee number and client id. */
    static byte[] mark(byte[] data, String employee, String client, boolean yes) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            Sheet s = wb.getSheet(AccessMatrixSheet.SHEET_ACCESS);
            Row header = s.getRow(0);
            int col = -1;
            for (Cell c : header) {
                if (c.getCellType() == CellType.STRING && c.getStringCellValue().contains(client)) {
                    col = c.getColumnIndex();
                }
            }
            assertThat(col).as("column for " + client).isNotNegative();
            for (Row r : s) {
                Cell first = r.getCell(0);
                if (first != null && first.getCellType() == CellType.STRING && employee.equals(first.getStringCellValue())) {
                    Cell c = r.getCell(col) == null ? r.createCell(col) : r.getCell(col);
                    c.setCellValue(yes ? "YES" : "");
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    static ImportItem item(ImportPreviewView p, String number, String client) {
        return p.items().stream().filter(i -> number.equals(i.employeeNumber()) && client.equals(i.clientId()))
                .findFirst().orElseThrow(() -> new AssertionError("no item " + number + "/" + client + " in "
                        + p.items()));
    }

    // ---- export ------------------------------------------------------------------------------

    @Test
    void exportIsProtectedSignedStampedAuditedAndCarriesNoNid() throws Exception {
        f.roster.set(1, new rw.gov.risa.sso.wrapper.policy.accessmatrix.RosterEmployee("E1002",
                "=HYPERLINK(\"http://evil\",\"x\")", "+SUM(1,2)", "@bob", null, "active", "M", null, "minagri"));
        AccessMatrixTransferService.Export x = transfer.export(f.ctx, f.caller, List.of("minagri-a"), "1.2.3.4");

        assertThat(x.filename()).startsWith("access-matrix-minagri-2026-10-20");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x.data()))) {
            Sheet s = wb.getSheet(AccessMatrixSheet.SHEET_ACCESS);
            assertThat(s.getProtect()).isTrue();
            for (Row r : s) {
                for (Cell c : r) {
                    // every cell is a value, never a formula: injection is impossible
                    assertThat(c.getCellType()).isNotEqualTo(CellType.FORMULA);
                    if (c.getCellType() == CellType.STRING) {
                        assertThat(c.getStringCellValue()).doesNotContain(MatrixFixture.NID);
                    }
                }
            }
            assertThat(wb.getSheet(AccessMatrixSheet.SHEET_META)).isNotNull();
        }
        var parsed = AccessMatrixWorkbook.parse(x.data(), KEY);
        assertThat(parsed.meta().institution()).isEqualTo("minagri");
        assertThat(parsed.meta().exportedAt()).startsWith("2026-10-20");
        ArgumentCaptor<AuditEvent.Builder> c = ArgumentCaptor.forClass(AuditEvent.Builder.class);
        verify(f.audit).emit(c.capture());
        AuditEvent e = c.getValue().build();
        assertThat(e.type()).isEqualTo(AuditEventType.ACCESS_MATRIX_EXPORTED);
        assertThat(e.extra()).containsEntry("export_id", x.exportId()).containsKey("file_sha256");
    }

    @Test
    void exportWithoutASigningKeyIs503() {
        AccessMatrixTransferService off = new AccessMatrixTransferService(f.service, f.provisioning, plans,
                new AccessMatrixProperties(""), f.audit);
        assertThatThrownBy(() -> off.export(f.ctx, f.caller, List.of("minagri-a"), null))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(503));
    }

    // ---- preview → apply: the stored plan, not the file -------------------------------------

    @Test
    void applyUsesTheStoredPlanNotTheFileAndRechecks() throws Exception {
        byte[] edited = mark(export(), "E1001", "minagri-b", true);

        ImportPreviewView p = transfer.preview(f.ctx, f.caller, edited, "matrix.xlsx", "  Jane   Director ");
        assertThat(p.importId()).isNotNull();
        assertThat(p.approvedBy()).isEqualTo("Jane Director");
        assertThat(item(p, "E1001", "minagri-b").outcome()).isEqualTo("will_grant");
        assertThat(p.summary()).containsEntry("will_grant", 1).containsEntry("already_has_access", 1);
        verify(f.kc, never()).admit(any(), anyString(), anyBoolean(), any());

        // The admin now edits the file again (another YES) — apply takes no file at all and uses the plan.
        byte[] tamperedAfterPreview = mark(edited, "E1001", "minagri-a", true);
        assertThat(tamperedAfterPreview).isNotEqualTo(edited);

        ImportReport r = transfer.apply(f.ctx, f.caller, p.importId(), "1.2.3.4");

        assertThat(r.results()).extracting(x -> x.employeeNumber() + "/" + x.clientId() + "/" + x.status())
                .containsExactly("E1001/minagri-b/applied");
        assertThat(r.batchId()).isEqualTo(p.importId());
        assertThat(r.channel()).isEqualTo("bulk");
        assertThat(r.action()).isEqualTo("grant");
        ArgumentCaptor<AuditEvent.Builder> c = ArgumentCaptor.forClass(AuditEvent.Builder.class);
        verify(f.audit, org.mockito.Mockito.atLeast(1)).emit(c.capture());
        List<AuditEvent> events = c.getAllValues().stream().map(AuditEvent.Builder::build).toList();
        assertThat(events).filteredOn(e -> e.type() == AuditEventType.ADMIN_CLIENT_UPDATED).singleElement()
                .satisfies(e -> assertThat(e.extra()).containsEntry("batch_id", p.importId())
                        .containsEntry("import_id", p.importId()).containsEntry("approved_by", "Jane Director")
                        .containsEntry("channel", "bulk"));
        assertThat(events).filteredOn(e -> e.type() == AuditEventType.ACCESS_MATRIX_IMPORTED).singleElement()
                .satisfies(e -> assertThat(e.extra()).containsEntry("import_id", p.importId())
                        .containsEntry("requested", 1));

        // consumed exactly once
        assertThatThrownBy(() -> transfer.apply(f.ctx, f.caller, p.importId(), null))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));
    }

    @Test
    void applyReChecksAtApplyTime() throws Exception {
        ImportPreviewView p = transfer.preview(f.ctx, f.caller, mark(export(), "E1001", "minagri-b", true), null,
                "Jane Director");
        // Someone granted it meanwhile: the concurrency guard rejects, nothing is written.
        f.holders.get("minagri-b").put("u-a", new Holder(true, List.of(), List.of(), false));
        ImportReport r = transfer.apply(f.ctx, f.caller, p.importId(), null);
        assertThat(r.results()).singleElement().satisfies(x -> assertThat(x.reason()).isEqualTo("state_changed"));
        verify(f.kc, never()).admit(any(), anyString(), anyBoolean(), any());
    }

    @Test
    void aClearedYesIsReportedNeverRevoked() throws Exception {
        byte[] cleared = mark(export(), "E1001", "minagri-a", false);
        ImportPreviewView p = transfer.preview(f.ctx, f.caller, cleared, null, "Jane Director");
        assertThat(item(p, "E1001", "minagri-a").outcome()).isEqualTo("removal_ignored");
        assertThat(p.summary()).containsEntry("removal_ignored", 1).containsEntry("will_grant", 0);
        assertThat(p.importId()).isNull(); // nothing to apply
        verify(f.kc, never()).revoke(any(), anyString(), anyBoolean(), any());
    }

    @Test
    void onlyThePreviewingAdminMayConfirm() throws Exception {
        ImportPreviewView p = transfer.preview(f.ctx, f.caller, mark(export(), "E1001", "minagri-b", true), null,
                "Jane Director");
        var other = new rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext("minagri.other", "u-o",
                "minagri", f.ctx.grant(), List.of("minagri"), false, List.of());
        assertThatThrownBy(() -> transfer.apply(other, f.caller, p.importId(), null))
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(403));
        // not consumed: the previewing admin still can
        assertThat(transfer.apply(f.ctx, f.caller, p.importId(), null).results()).hasSize(1);
    }

    // ---- refusals ----------------------------------------------------------------------------

    @Test
    void aFileTamperedBeforePreviewHasABadSignature() throws Exception {
        byte[] data = export();
        byte[] tampered;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            Sheet meta = wb.getSheet(AccessMatrixSheet.SHEET_META);
            for (Row r : meta) {
                for (Cell c : r) {
                    if (c.getCellType() == CellType.STRING && c.getStringCellValue().contains("minagri-b")) {
                        c.setCellValue(c.getStringCellValue().replace("minagri-b", "minagri-ro"));
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            tampered = out.toByteArray();
        }
        assertThatThrownBy(() -> transfer.preview(f.ctx, f.caller, tampered, null, "Jane Director"))
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(400);
                    assertThat(((ApiException) e).error()).isEqualTo("not_exported_here");
                });
        assertThat(redisData).isEmpty();
    }

    @Test
    void anotherInstitutionsFileAndAnOldFileAreRefused() {
        byte[] theirs = AccessMatrixWorkbook.build(new SheetMeta("minisante", List.of("minisante-hmis"), "x",
                        "2026-10-19T08:00:00+00:00", "minisante.admin"),
                List.of(new SheetColumn("minisante-hmis", "HMIS", true)),
                List.of(new SheetRow("MS1", "A", "B", null, "active", AccountState.EXISTING, false,
                        Map.of("minisante-hmis", false))), KEY);
        assertThatThrownBy(() -> transfer.preview(f.ctx, f.caller, theirs, null, "Jane Director"))
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(403);
                    assertThat(((ApiException) e).error()).isEqualTo("other_institution_file");
                });

        byte[] old = AccessMatrixWorkbook.build(new SheetMeta("minagri", List.of("minagri-a"), "x",
                        "2026-09-01T08:00:00+00:00", "minagri.admin"),
                List.of(new SheetColumn("minagri-a", "A", true)),
                List.of(new SheetRow("E1001", "A", "B", null, "active", AccountState.EXISTING, true,
                        Map.of("minagri-a", true))), KEY);
        assertThatThrownBy(() -> transfer.preview(f.ctx, f.caller, old, null, "Jane Director"))
                .satisfies(e -> assertThat(((ApiException) e).error()).isEqualTo("export_expired"));

        assertThatThrownBy(() -> transfer.preview(f.ctx, f.caller, export(), null, " "))
                .satisfies(e -> assertThat(((ApiException) e).error()).isEqualTo("approved_by_required"));
    }

    @Test
    void missingNidAndEmailForNewAccountsShowInThePreview() throws Exception {
        byte[] edited = mark(export(), "E1002", "minagri-b", true);
        ReportRow denied = new ReportRow("E1002", "Bob", "bob@minagri.gov.rw", PlanAction.SKIP, AccountStatus.NOT_CREATED,
                "no_qualifying_client", "none qualifies", List.of(), List.of(),
                List.of(new ClientDecision("minagri-b", JoinColumn.NID, false, false, DenyReason.VALUE_MISSING,
                        "minagri-b identifies users by NID and Bob has no national_id")), Outcome.SKIPPED, null, Map.of());
        when(f.provisioning.run(eq(f.ctx), any())).thenReturn(new ProvisionReport("minagri", true, List.of(),
                new BatchTotals(1, 0, 0, 1, 0, 1, 0, 0, 0, List.of("minagri-b")), List.of(denied)));

        ImportPreviewView p = transfer.preview(f.ctx, f.caller, edited, null, "Jane Director");

        ImportItem bob = item(p, "E1002", "minagri-b");
        assertThat(bob.outcome()).isEqualTo("rejected");
        assertThat(bob.reason()).isEqualTo("join_value_missing");
        ArgumentCaptor<EmployeeProvisioningService.Command> cmd =
                ArgumentCaptor.forClass(EmployeeProvisioningService.Command.class);
        verify(f.provisioning).run(eq(f.ctx), cmd.capture());
        assertThat(cmd.getValue().dryRun()).isTrue(); // a preview never writes
        assertThat(p.importId()).isNull();
    }
}
