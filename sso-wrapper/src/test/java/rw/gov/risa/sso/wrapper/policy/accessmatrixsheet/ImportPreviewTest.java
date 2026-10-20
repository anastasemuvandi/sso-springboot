package rw.gov.risa.sso.wrapper.policy.accessmatrixsheet;

import static org.assertj.core.api.Assertions.assertThat;
import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixWorkbookTest.PORTAL;
import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixWorkbookTest.REPORTS;
import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixWorkbookTest.SECRET;
import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixWorkbookTest.edit;
import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixWorkbookTest.export;
import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixWorkbookTest.mark;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountMatch;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.BulkItem;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RosterEmployee;

class ImportPreviewTest {

    final Map<String, RosterEmployee> roster = new LinkedHashMap<>();
    final Map<String, AccountMatch> accounts = new LinkedHashMap<>();
    /** client -> users with access. */
    final Map<String, Set<String>> access = new HashMap<>();
    final Map<String, Boolean> editable = new LinkedHashMap<>(Map.of(PORTAL, true, REPORTS, true));
    final List<Map<String, List<String>>> dryRunCalls = new ArrayList<>();

    ImportPreviewTest() {
        put("E001", "Alice", "a@x", "1199", "active", AccountMatch.existing("u1"));
        put("E002", "Bob", "b@x", null, "active", AccountMatch.existing("u2"));
        put("E003", "Carol", "c@x", "1199", "active", AccountMatch.existing("u3"));
        put("E004", "Nadia", "n@x", "1199", "active", AccountMatch.NONE);
        put("E005", "Leo", null, null, "terminated", AccountMatch.existing("u5"));
        put("E006", "Ghost", null, null, "active", AccountMatch.ELSEWHERE);
        put("E008", "NoNid", "nn@x", null, "active", AccountMatch.NONE);
        put("E009", "NoMail", null, "1", "active", AccountMatch.NONE);
        access.put(PORTAL, Set.of("u1", "u2"));
        access.put(REPORTS, Set.of("u3"));
    }

    void put(String n, String first, String email, String nid, String status, AccountMatch m) {
        roster.put(n, new RosterEmployee(n, first, "X", email, nid, status, null, null, "minagri"));
        accounts.put(n, m);
    }

    /** The reference test's DryRunProvisioner: REPORTS denied without an NID, no email refused. */
    final Function<Map<String, List<String>>, List<ImportPreview.CreationVerdict>> dryRun = wanted -> {
        dryRunCalls.add(wanted);
        List<ImportPreview.CreationVerdict> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> w : wanted.entrySet()) {
            RosterEmployee e = roster.get(w.getKey());
            if (e.email() == null) {
                out.addAll(ImportPreview.foldDryRun(w.getKey(), w.getValue(), Set.of(), Map.of(),
                        "no_email", "No email to name an account after."));
                continue;
            }
            Map<String, String[]> denied = new HashMap<>();
            Set<String> granted = new java.util.HashSet<>();
            for (String c : w.getValue()) {
                if (c.equals(REPORTS) && e.nationalId() == null) {
                    denied.put(c, new String[] {"join_value_missing", "No NID on record."});
                } else {
                    granted.add(c);
                }
            }
            out.addAll(ImportPreview.foldDryRun(w.getKey(), w.getValue(), granted, denied, null, null));
        }
        return out;
    };

    ImportPreview.Preview preview(byte[] data, boolean mayCreate) {
        ParsedSheet parsed = AccessMatrixWorkbook.parse(data, SECRET);
        assertThat(ImportPreview.frameRefusal(parsed, "minagri", Instant.now())).isEmpty();
        ImportPreview.Result r = ImportPreview.plan(parsed, editable, roster, accounts,
                (c, u) -> access.getOrDefault(c, Set.of()).contains(u), mayCreate, dryRun);
        assertThat(r).isInstanceOf(ImportPreview.Planned.class);
        return ((ImportPreview.Planned) r).preview();
    }

    static ImportPreview.Item item(ImportPreview.Preview p, String number, String client) {
        return p.items().stream().filter(i -> number.equals(i.employeeNumber()) && client.equals(i.clientId()))
                .findFirst().orElseThrow();
    }

    byte[] exportRoster() {
        return export(List.of(PORTAL, REPORTS));
    }

    @Test
    void each_mark_is_judged() throws IOException {
        byte[] data = edit(exportRoster(), mark("E003", PORTAL, "YES")
                .andThen(mark("E004", PORTAL, "yes"))
                .andThen(mark("E004", REPORTS, "YES"))
                .andThen(mark("E005", PORTAL, "YES"))
                .andThen(mark("E006", PORTAL, "YES"))
                .andThen(mark("E002", PORTAL, null))
                .andThen(mark("E001", REPORTS, "perhaps")));
        ImportPreview.Preview p = preview(data, true);
        assertThat(item(p, "E003", PORTAL).outcome()).isEqualTo("will_grant");
        assertThat(item(p, "E004", PORTAL).outcome()).isEqualTo("will_create_account");
        assertThat(item(p, "E004", REPORTS).outcome()).isEqualTo("will_create_account");
        assertThat(item(p, "E005", PORTAL).reason()).isEqualTo("employee_left");
        assertThat(item(p, "E006", PORTAL).reason()).isEqualTo("account_unresolved");
        assertThat(item(p, "E002", PORTAL).outcome()).isEqualTo("removal_ignored");
        ImportPreview.Item bad = item(p, "E001", REPORTS);
        assertThat(bad.outcome()).isEqualTo("invalid");
        assertThat(bad.message()).contains("(found: 'perhaps')");
        assertThat(p.summary()).containsEntry("already_has_access", 2); // Alice's PORTAL, Carol's REPORTS
        assertThat(p.items()).noneMatch(i -> i.outcome().equals("already_has_access")); // counted only
        assertThat(p.summary().keySet()).containsExactly("will_grant", "will_create_account", "already_has_access",
                "removal_ignored", "rejected", "invalid");
        // A cleared YES is reported, never planned: the plan holds grants only.
        assertThat(p.plan()).containsExactlyInAnyOrder(new BulkItem("E003", PORTAL), new BulkItem("E004", PORTAL),
                new BulkItem("E004", REPORTS));
        // Order: invalid, rejected, removal_ignored, create, grant.
        assertThat(p.items()).extracting(ImportPreview.Item::outcome).containsExactly(
                "invalid", "rejected", "rejected", "removal_ignored", "will_create_account", "will_create_account",
                "will_grant");
        assertThat(item(p, "E003", PORTAL).name()).isEqualTo("Carol X");
        assertThat(item(p, "E003", PORTAL).row()).isEqualTo(4);
        assertThat(item(p, "E003", PORTAL).message()).isEqualTo("Will be given access.");
    }

    @Test
    void provisionings_own_refusals_show_before_confirming() throws IOException {
        byte[] data = edit(exportRoster(), wb -> {
            XSSFSheet ws = wb.getSheet(AccessMatrixSheet.SHEET_ACCESS);
            XSSFRow r8 = ws.createRow(7);
            r8.createCell(0).setCellValue("E008");
            r8.createCell(7).setCellValue("YES");
            r8.createCell(8).setCellValue("YES");
            XSSFRow r9 = ws.createRow(8);
            r9.createCell(0).setCellValue("E009");
            r9.createCell(7).setCellValue("YES");
        });
        ImportPreview.Preview p = preview(data, true);
        assertThat(item(p, "E008", REPORTS).reason()).isEqualTo("join_value_missing");
        assertThat(item(p, "E008", REPORTS).message()).isEqualTo("No NID on record.");
        assertThat(item(p, "E008", PORTAL).outcome()).isEqualTo("will_create_account");
        assertThat(item(p, "E009", PORTAL).reason()).isEqualTo("no_email");
        assertThat(item(p, "E008", PORTAL).row()).isEqualTo(8);
        assertThat(dryRunCalls).hasSize(1);
    }

    @Test
    void a_row_for_someone_not_on_the_roster_is_reported_never_matched_by_name() throws IOException {
        byte[] data = edit(exportRoster(), wb -> {
            XSSFRow r = wb.getSheet(AccessMatrixSheet.SHEET_ACCESS).createRow(7);
            r.createCell(0).setCellValue("E100");
            r.createCell(1).setCellValue("Alice");
            r.createCell(7).setCellValue("YES");
        });
        ImportPreview.Preview p = preview(data, true);
        assertThat(item(p, "E100", PORTAL).reason()).isEqualTo("not_in_roster");
        assertThat(item(p, "E100", PORTAL).name()).isNull();
        assertThat(p.plan()).isEmpty(); // nothing applicable -> no import id
        assertThat(dryRunCalls).isEmpty();
    }

    @Test
    void a_tier_that_may_not_create_accounts_is_told_in_the_preview() throws IOException {
        ImportPreview.Preview p = preview(edit(exportRoster(), mark("E004", PORTAL, "YES")), false);
        assertThat(item(p, "E004", PORTAL).reason()).isEqualTo("cannot_create_account");
        assertThat(p.plan()).isEmpty();
    }

    @Test
    void a_read_only_column_is_rejected_per_mark() throws IOException {
        editable.put(PORTAL, false);
        ImportPreview.Preview p = preview(edit(exportRoster(), mark("E003", PORTAL, "YES")), true);
        assertThat(item(p, "E003", PORTAL).reason()).isEqualTo("not_manageable");
    }

    @Test
    void an_app_that_left_my_scope_since_the_export_refuses_the_file() {
        editable.remove(REPORTS);
        ParsedSheet parsed = AccessMatrixWorkbook.parse(exportRoster(), SECRET);
        ImportPreview.Result r = ImportPreview.plan(parsed, editable, roster, accounts, (c, u) -> false, true, dryRun);
        assertThat(r).isInstanceOf(ImportPreview.Refused.class);
        ImportPreview.Refusal refusal = ((ImportPreview.Refused) r).refusal();
        assertThat(refusal.httpStatus()).isEqualTo(403);
        assertThat(refusal.code()).isEqualTo("client_outside_scope");
    }

    @Test
    void another_institutions_file_is_403() {
        ParsedSheet parsed = AccessMatrixWorkbook.parse(exportRoster(), SECRET);
        ImportPreview.Refusal r = ImportPreview.frameRefusal(parsed, "minisante", Instant.now()).orElseThrow();
        assertThat(r.httpStatus()).isEqualTo(403);
        assertThat(r.code()).isEqualTo("other_institution_file");
        assertThat(r.message()).contains("'minagri', not 'minisante'");
    }

    @Test
    void a_stale_export_is_refused() {
        Instant old = Instant.now().minus(31, ChronoUnit.DAYS);
        SheetMeta meta = new SheetMeta("minagri", List.of(PORTAL), "x", SheetMeta.isoUtc(old), "me");
        byte[] data = AccessMatrixWorkbook.build(meta, List.of(new SheetColumn(PORTAL, null, true)), List.of(), SECRET);
        ParsedSheet parsed = AccessMatrixWorkbook.parse(data, SECRET); // still a valid file
        ImportPreview.Refusal r = ImportPreview.frameRefusal(parsed, "minagri", Instant.now()).orElseThrow();
        assertThat(r.httpStatus()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("export_expired");
        assertThat(r.message()).contains("days ago");
    }

    @Test
    void no_exported_column_left() throws IOException {
        byte[] data = edit(export(List.of(PORTAL)),
                wb -> wb.getSheet(AccessMatrixSheet.SHEET_ACCESS).getRow(0).getCell(7).setCellValue("renamed"));
        ParsedSheet parsed = AccessMatrixWorkbook.parse(data, SECRET);
        assertThat(ImportPreview.frameRefusal(parsed, "minagri", Instant.now()))
                .hasValueSatisfying(r -> assertThat(r.code()).isEqualTo("no_columns_left"));
    }

    @Test
    void who_approved_it_is_required() {
        for (String who : new String[] {"", " ", "x", null}) {
            assertThat(ImportPreview.approverRefusal(ImportPreview.normaliseApprover(who))).isPresent();
        }
        assertThat(ImportPreview.normaliseApprover("  Director   General\tUwimana ")).isEqualTo("Director General Uwimana");
        assertThat(ImportPreview.approverRefusal("HR")).isEmpty();
        assertThat(ImportPreview.approverRefusal("x".repeat(121))).isPresent();
    }

    @Test
    void more_than_2000_grants_is_refused() {
        Map<String, Map<String, Boolean>> marks = new LinkedHashMap<>();
        for (int i = 0; i < 2001; i++) {
            String n = "N" + i;
            roster.put(n, new RosterEmployee(n, null, null, null, null, "active", null, null, null));
            accounts.put(n, AccountMatch.existing("x" + i));
            marks.put(n, Map.of(PORTAL, true));
        }
        ParsedSheet parsed = new ParsedSheet(new SheetMeta("minagri", List.of(PORTAL), "x", "t", "me"),
                List.of(PORTAL), marks, Map.of(), List.of());
        ImportPreview.Result r = ImportPreview.plan(parsed, editable, roster, accounts, (c, u) -> false, true, dryRun);
        assertThat(((ImportPreview.Refused) r).refusal().code()).isEqualTo("too_many_grants");
    }

    @Test
    void dry_run_batches_group_by_client_set_and_chunk() {
        Map<String, List<String>> wanted = new LinkedHashMap<>();
        wanted.put("E1", List.of("b", "a"));
        wanted.put("E2", List.of("a", "b"));
        wanted.put("E3", List.of("a"));
        wanted.put("E4", List.of("a", "b"));
        List<Map.Entry<List<String>, List<String>>> batches = ImportPreview.dryRunBatches(wanted, 2);
        assertThat(batches).containsExactly(
                Map.entry(List.of("a", "b"), List.of("E1", "E2")),
                Map.entry(List.of("a", "b"), List.of("E4")),
                Map.entry(List.of("a"), List.of("E3")));
    }

    @Test
    void fold_defaults() {
        List<ImportPreview.CreationVerdict> v = ImportPreview.foldDryRun("E1", List.of("a", "b"), Set.of(),
                Map.of("a", new String[] {null}), null, null);
        assertThat(v.get(0).reason()).isEqualTo("denied");
        assertThat(v.get(1).reason()).isEqualTo("not_provisioned");
        assertThat(v.get(1).message()).isEqualTo("No account could be created for this employee.");
    }
}
