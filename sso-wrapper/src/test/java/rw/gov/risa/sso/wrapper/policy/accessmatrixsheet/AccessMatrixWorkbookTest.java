package rw.gov.risa.sso.wrapper.policy.accessmatrixsheet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountState;

class AccessMatrixWorkbookTest {

    static final String SECRET = "test-secret";
    static final String A = "minagri";
    static final String PORTAL = "minagri-portal";
    static final String REPORTS = "minagri-reports";
    static final String SHARED = "irembo";

    static SheetMeta meta(List<String> clients) {
        return new SheetMeta(A, clients, "exp-1", SheetMeta.isoUtc(Instant.now()), "admin." + A);
    }

    static List<SheetRow> rows() {
        return List.of(
                new SheetRow("E001", "Alice", "Mukamana", "alice@a.gov.rw", "active", AccountState.EXISTING, true,
                        Map.of(PORTAL, true)),
                new SheetRow("E002", "Bob", "Habimana", "bob@a.gov.rw", "active", AccountState.EXISTING, false,
                        Map.of(PORTAL, true)),
                new SheetRow("E003", "Carol", "Uwase", "carol@a.gov.rw", "active", AccountState.EXISTING, true,
                        Map.of(REPORTS, true)),
                new SheetRow("E004", "Nadia", "Ingabire", "nadia@a.gov.rw", "active", AccountState.NONE, true, Map.of()),
                new SheetRow("E005", "Leo", "Left", null, "terminated", AccountState.EXISTING, false, Map.of(),
                        "IPPIS reports no open post for this employee here; a leaver is not given access."),
                new SheetRow("E006", "Ghost", "Elsewhere", null, "active", AccountState.ELSEWHERE, false, Map.of(),
                        "Linked to an SSO account outside this institution."));
    }

    static byte[] export(List<String> clients) {
        List<SheetColumn> cols = new ArrayList<>();
        for (String c : clients) {
            cols.add(new SheetColumn(c, c.toUpperCase(), !c.equals(SHARED)));
        }
        return AccessMatrixWorkbook.build(meta(clients), cols, rows(), SECRET);
    }

    static XSSFWorkbook open(byte[] data) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(data));
    }

    /** Edit a workbook as a person would in Excel. */
    static byte[] edit(byte[] data, Consumer<XSSFWorkbook> change) throws IOException {
        try (XSSFWorkbook wb = open(data)) {
            change.accept(wb);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    static Consumer<XSSFWorkbook> mark(String number, String client, String value) {
        return wb -> {
            XSSFSheet ws = wb.getSheet(AccessMatrixSheet.SHEET_ACCESS);
            int col = -1;
            for (int i = 0; i < ws.getRow(0).getLastCellNum(); i++) {
                if (client.equals(ws.getRow(0).getCell(i).getStringCellValue())) {
                    col = i;
                }
            }
            for (int r = 1; r <= ws.getLastRowNum(); r++) {
                XSSFRow row = ws.getRow(r);
                if (number.equals(row.getCell(0).getStringCellValue())) {
                    XSSFCell cell = row.getCell(col) == null ? row.createCell(col) : row.getCell(col);
                    if (value == null) {
                        cell.setBlank();
                    } else {
                        cell.setCellValue(value);
                    }
                }
            }
        };
    }

    static Consumer<XSSFWorkbook> setMeta(String key, String value) {
        return wb -> {
            XSSFSheet ws = wb.getSheet(AccessMatrixSheet.SHEET_META);
            for (int r = 0; r <= ws.getLastRowNum(); r++) {
                if (key.equals(ws.getRow(r).getCell(0).getStringCellValue())) {
                    ws.getRow(r).getCell(1).setCellValue(value);
                }
            }
        };
    }

    @Nested
    class TheFile {
        @Test
        void one_row_per_employee_and_yes_where_they_have_access() throws IOException {
            try (XSSFWorkbook wb = open(export(List.of(PORTAL, REPORTS)))) {
                XSSFSheet ws = wb.getSheet(AccessMatrixSheet.SHEET_ACCESS);
                List<String> header = new ArrayList<>();
                ws.getRow(0).forEach(c -> header.add(c.getStringCellValue()));
                List<String> expected = new ArrayList<>(AccessMatrixSheet.IDENTITY_HEADERS);
                expected.addAll(List.of(PORTAL, REPORTS));
                assertThat(header).isEqualTo(expected);
                assertThat(ws.getLastRowNum()).isEqualTo(6);
                assertThat(ws.getRow(1).getCell(7).getStringCellValue()).isEqualTo("YES");
                assertThat(ws.getRow(3).getCell(7).getCellType()).isEqualTo(CellType.BLANK);
                assertThat(ws.getRow(3).getCell(8).getStringCellValue()).isEqualTo("YES");
                assertThat(ws.getRow(4).getCell(5).getStringCellValue()).isEqualTo("no (created when granted)");
                assertThat(ws.getRow(6).getCell(5).getStringCellValue())
                        .isEqualTo("locked: account belongs to another institution");
                assertThat(wb.getActiveSheetIndex()).isZero();
                assertThat(wb.getSheet(AccessMatrixSheet.SHEET_HELP).getRow(0).getCell(0).getStringCellValue())
                        .isEqualTo("Access matrix for minagri");
            }
        }

        @Test
        void no_national_id_leaves_the_system_only_whether_one_is_on_file() throws IOException {
            byte[] data = AccessMatrixWorkbook.build(meta(List.of(PORTAL)), List.of(new SheetColumn(PORTAL, null, true)),
                    rows(), SECRET);
            try (XSSFWorkbook wb = open(data)) {
                XSSFSheet ws = wb.getSheet(AccessMatrixSheet.SHEET_ACCESS);
                assertThat(ws.getRow(0).getCell(6).getStringCellValue()).isEqualTo("National ID on file");
                assertThat(ws.getRow(1).getCell(6).getStringCellValue()).isEqualTo("yes");
                assertThat(ws.getRow(2).getCell(6).getStringCellValue()).isEqualTo("no");
            }
            // SheetRow has no NID field at all; nothing NID-shaped is in the archive.
            assertThat(unzipText(data)).doesNotContainPattern("\\d{16}");
        }

        @Test
        void identity_columns_and_blocked_rows_are_locked_app_cells_are_not() throws IOException {
            try (XSSFWorkbook wb = open(export(List.of(PORTAL, SHARED)))) {
                XSSFSheet ws = wb.getSheet(AccessMatrixSheet.SHEET_ACCESS);
                assertThat(ws.getProtect()).isTrue();
                assertThat(ws.isAutoFilterLocked()).isFalse();
                assertThat(ws.isSortLocked()).isFalse();
                int app = AccessMatrixSheet.IDENTITY_HEADERS.size();
                assertThat(ws.getRow(3).getCell(0).getCellStyle().getLocked()).isTrue();        // E003 identity
                assertThat(ws.getRow(3).getCell(app).getCellStyle().getLocked()).isFalse();     // E003 portal
                assertThat(ws.getRow(1).getCell(app).getCellStyle().getLocked()).isFalse();     // existing YES clearable
                assertThat(ws.getRow(5).getCell(app).getCellStyle().getLocked()).isTrue();      // leaver
                assertThat(ws.getRow(6).getCell(app).getCellStyle().getLocked()).isTrue();      // account elsewhere
                assertThat(ws.getRow(3).getCell(app + 1).getCellStyle().getLocked()).isTrue();  // view-only column
                assertThat(ws.getRow(5).getCell(0).getCellComment().getString().getString()).contains("leaver");
                assertThat(ws.getRow(0).getCell(app + 1).getCellComment().getString().getString())
                        .contains("view only for your role");
                assertThat(ws.getPaneInformation().getVerticalSplitLeftColumn()).isEqualTo((short) app);
            }
        }

        @Test
        void yes_dropdown_only_on_editable_columns() throws IOException {
            try (XSSFWorkbook wb = open(export(List.of(PORTAL, SHARED)))) {
                List<? extends DataValidation> dvs = wb.getSheet(AccessMatrixSheet.SHEET_ACCESS).getDataValidations();
                assertThat(dvs).hasSize(1);
                DataValidation dv = dvs.get(0);
                assertThat(dv.getValidationConstraint().getExplicitListValues()).containsExactly("YES");
                assertThat(dv.getRegions().getCellRangeAddress(0).getFirstColumn())
                        .isEqualTo(AccessMatrixSheet.IDENTITY_HEADERS.size());
                assertThat(dv.getRegions().getCellRangeAddress(0).getFirstRow()).isEqualTo(1);
                assertThat(dv.getRegions().getCellRangeAddress(0).getLastRow()).isEqualTo(6);
                assertThat(dv.getEmptyCellAllowed()).isTrue();
            }
        }

        @Test
        void the_metadata_sheet_is_hidden_and_signed() throws IOException {
            byte[] data = export(List.of(PORTAL));
            try (XSSFWorkbook wb = open(data)) {
                int idx = wb.getSheetIndex(AccessMatrixSheet.SHEET_META);
                assertThat(wb.getSheetVisibility(idx)).isEqualTo(SheetVisibility.VERY_HIDDEN);
                assertThat(wb.getSheetAt(idx).getProtect()).isTrue();
            }
            ParsedSheet parsed = AccessMatrixWorkbook.parse(data, SECRET);
            assertThat(parsed.meta().institution()).isEqualTo(A);
            assertThat(parsed.meta().clients()).containsExactly(PORTAL);
            assertThat(parsed.meta().exportedBy()).isEqualTo("admin." + A);
        }

        @Test
        void a_name_that_looks_like_a_formula_is_written_as_text() throws IOException {
            SheetRow row = new SheetRow("E1", "=HYPERLINK(\"http://evil\",\"click\")", "+cmd|' /C calc'!A0",
                    "@SUM(1)", "-2+3", AccountState.EXISTING, false, Map.of());
            byte[] data = AccessMatrixWorkbook.build(meta(List.of(PORTAL)), List.of(new SheetColumn(PORTAL, null, true)),
                    List.of(row), SECRET);
            try (XSSFWorkbook wb = open(data)) {
                XSSFRow r = wb.getSheet(AccessMatrixSheet.SHEET_ACCESS).getRow(1);
                for (int c = 1; c <= 4; c++) {
                    assertThat(r.getCell(c).getCellType()).isEqualTo(CellType.STRING);
                }
                assertThat(r.getCell(1).getStringCellValue()).startsWith("=HYPERLINK");
            }
            assertThat(unzipText(data)).doesNotContain("<f>");
        }

        @Test
        void an_empty_export_still_has_a_dropdown_range() throws IOException {
            byte[] data = AccessMatrixWorkbook.build(meta(List.of(PORTAL)), List.of(new SheetColumn(PORTAL, null, true)),
                    List.of(), SECRET);
            ParsedSheet parsed = AccessMatrixWorkbook.parse(data, SECRET);
            assertThat(parsed.marks()).isEmpty();
            assertThat(parsed.problems()).isEmpty();
            assertThat(parsed.clients()).containsExactly(PORTAL);
        }
    }

    @Nested
    class RoundTrip {
        @Test
        void export_edit_import() throws IOException {
            byte[] data = edit(export(List.of(PORTAL, REPORTS)),
                    mark("E003", PORTAL, "YES").andThen(mark("E004", PORTAL, "yes"))
                            .andThen(mark("E002", PORTAL, null)).andThen(mark("E001", REPORTS, "perhaps")));
            ParsedSheet p = AccessMatrixWorkbook.parse(data, SECRET);
            assertThat(p.clients()).containsExactly(PORTAL, REPORTS);
            assertThat(p.marks().keySet()).containsExactly("E001", "E002", "E003", "E004", "E005", "E006");
            assertThat(p.marks().get("E003")).containsEntry(PORTAL, true).containsEntry(REPORTS, true);
            assertThat(p.marks().get("E004")).containsEntry(PORTAL, true).containsEntry(REPORTS, false);
            assertThat(p.marks().get("E002")).containsEntry(PORTAL, false);
            assertThat(p.marks().get("E001")).containsOnlyKeys(PORTAL); // the unreadable cell is a problem
            assertThat(p.rows()).containsEntry("E001", 2).containsEntry("E006", 7);
            assertThat(p.problems()).singleElement().satisfies(pr -> {
                assertThat(pr.reason()).isEqualTo("invalid_value");
                assertThat(pr.row()).isEqualTo(2);
                assertThat(pr.employeeNumber()).isEqualTo("E001");
                assertThat(pr.clientId()).isEqualTo(REPORTS);
                assertThat(pr.value()).isEqualTo("perhaps");
            });
        }

        @Test
        void a_numeric_employee_number_and_numeric_marks_are_read() throws IOException {
            byte[] data = edit(export(List.of(PORTAL)), wb -> {
                XSSFSheet ws = wb.getSheet(AccessMatrixSheet.SHEET_ACCESS);
                XSSFRow r = ws.createRow(7);
                r.createCell(0).setCellValue(12345.0);
                r.createCell(7).setCellValue(1.0);
            });
            ParsedSheet p = AccessMatrixWorkbook.parse(data, SECRET);
            assertThat(p.marks().get("12345")).containsEntry(PORTAL, true);
        }

        @Test
        void row_problems() throws IOException {
            byte[] data = edit(export(List.of(PORTAL)), wb -> {
                XSSFSheet ws = wb.getSheet(AccessMatrixSheet.SHEET_ACCESS);
                XSSFRow dup = ws.createRow(7);
                dup.createCell(0).setCellValue("E003");
                dup.createCell(7).setCellValue("YES");
                XSSFRow nameless = ws.createRow(8);
                nameless.createCell(1).setCellValue("Somebody");
                nameless.createCell(7).setCellValue("YES");
                XSSFRow empty = ws.createRow(9);
                empty.createCell(1).setCellValue("Just a name");
                ws.getRow(0).createCell(8).setCellValue("extra-app");
            });
            ParsedSheet p = AccessMatrixWorkbook.parse(data, SECRET);
            assertThat(p.problems()).extracting(Problem::reason, Problem::row).containsExactly(
                    org.assertj.core.groups.Tuple.tuple("unknown_column", 1),
                    org.assertj.core.groups.Tuple.tuple("duplicate_row", 8),
                    org.assertj.core.groups.Tuple.tuple("no_employee_number", 9));
            assertThat(p.problems().get(0).value()).isEqualTo("extra-app");
            assertThat(p.problems().get(0).message()).contains("ignored");
            assertThat(p.marks().get("E003")).containsEntry(PORTAL, false); // first row wins
        }

        @Test
        void a_removed_column_is_reported_and_not_read() throws IOException {
            byte[] data = edit(export(List.of(PORTAL, REPORTS)), wb -> {
                XSSFSheet ws = wb.getSheet(AccessMatrixSheet.SHEET_ACCESS);
                ws.getRow(0).getCell(8).setCellValue("");
            });
            ParsedSheet p = AccessMatrixWorkbook.parse(data, SECRET);
            assertThat(p.clients()).containsExactly(PORTAL);
            assertThat(p.problems()).singleElement().satisfies(pr -> {
                assertThat(pr.reason()).isEqualTo("missing_column");
                assertThat(pr.clientId()).isEqualTo(REPORTS);
            });
        }

        @Test
        void a_long_invalid_value_is_cut_to_60() throws IOException {
            byte[] data = edit(export(List.of(PORTAL)), mark("E003", PORTAL, "z".repeat(100)));
            assertThat(AccessMatrixWorkbook.parse(data, SECRET).problems().get(0).value()).hasSize(60);
        }
    }

    @Nested
    class RefusedFiles {
        @Test
        void empty_and_not_an_xlsx() {
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(new byte[0], SECRET))
                    .isInstanceOf(SheetError.class).extracting("code").isEqualTo(SheetError.EMPTY_FILE);
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(
                    "Employee number,portal\nE003,YES\n".getBytes(StandardCharsets.UTF_8), SECRET))
                    .isInstanceOf(SheetError.class).hasMessageContaining(".xlsx")
                    .extracting("code").isEqualTo(SheetError.NOT_XLSX);
        }

        @Test
        void too_large() {
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(new byte[5 * 1024 * 1024 + 1], SECRET))
                    .isInstanceOf(SheetError.class).hasMessageContaining("5 MB");
        }

        @Test
        void a_workbook_that_was_not_exported_here() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (XSSFWorkbook wb = new XSSFWorkbook()) {
                wb.createSheet("Sheet1");
                wb.write(out);
            }
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(out.toByteArray(), SECRET))
                    .isInstanceOf(SheetError.class).hasMessageContaining("not exported")
                    .extracting("code").isEqualTo(SheetError.NOT_EXPORTED_HERE);
        }

        @Test
        void a_wrong_secret_is_a_bad_signature() {
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(export(List.of(PORTAL)), "other-secret"))
                    .isInstanceOf(SheetError.class).extracting("code").isEqualTo(SheetError.NOT_EXPORTED_HERE);
        }

        @Test
        void relabelling_the_institution_breaks_the_signature() throws IOException {
            byte[] data = edit(export(List.of(PORTAL)), setMeta("institution", "minisante"));
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(data, SECRET))
                    .isInstanceOf(SheetError.class).extracting("code").isEqualTo(SheetError.NOT_EXPORTED_HERE);
        }

        @Test
        void adding_an_app_to_the_signed_list_breaks_the_signature() throws IOException {
            byte[] data = edit(export(List.of(PORTAL)), setMeta("clients", "[\"minagri-portal\", \"minagri-unused\"]"));
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(data, SECRET))
                    .isInstanceOf(SheetError.class).extracting("code").isEqualTo(SheetError.NOT_EXPORTED_HERE);
        }

        @Test
        void tampered_signature_or_date() throws IOException {
            byte[] sig = edit(export(List.of(PORTAL)), setMeta("signature", "0".repeat(64)));
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(sig, SECRET)).isInstanceOf(SheetError.class);
            byte[] date = edit(export(List.of(PORTAL)), setMeta("exported_at", "2099-01-01T00:00:00+00:00"));
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(date, SECRET)).isInstanceOf(SheetError.class);
            byte[] junk = edit(export(List.of(PORTAL)), setMeta("clients", "not json"));
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(junk, SECRET))
                    .isInstanceOf(SheetError.class).extracting("code").isEqualTo(SheetError.NOT_EXPORTED_HERE);
        }

        @Test
        void a_different_version() throws IOException {
            byte[] data = edit(export(List.of(PORTAL)), setMeta("version", "2"));
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(data, SECRET))
                    .isInstanceOf(SheetError.class).extracting("code").isEqualTo(SheetError.WRONG_VERSION);
        }

        @Test
        void a_missing_access_sheet_or_employee_column() throws IOException {
            byte[] noSheet = edit(export(List.of(PORTAL)), wb -> wb.setSheetName(0, "Renamed"));
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(noSheet, SECRET))
                    .isInstanceOf(SheetError.class).extracting("code").isEqualTo(SheetError.ACCESS_SHEET_MISSING);
            byte[] noColumn = edit(export(List.of(PORTAL)),
                    wb -> wb.getSheet(AccessMatrixSheet.SHEET_ACCESS).getRow(0).getCell(0).setCellValue("Number"));
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(noColumn, SECRET))
                    .isInstanceOf(SheetError.class).extracting("code").isEqualTo(SheetError.EMPLOYEE_COLUMN_MISSING);
        }

        @Test
        void a_macro_workbook_is_refused() throws IOException {
            byte[] data = export(List.of(PORTAL));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(data));
                 ZipOutputStream zo = new ZipOutputStream(out)) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    zo.putNextEntry(new ZipEntry(e.getName()));
                    zo.write(in.readAllBytes());
                    zo.closeEntry();
                }
                zo.putNextEntry(new ZipEntry("xl/vbaProject.bin"));
                zo.write(new byte[] {0});
                zo.closeEntry();
            }
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(out.toByteArray(), SECRET))
                    .isInstanceOf(SheetError.class).hasMessageContaining("macros");
        }

        @Test
        void a_zip_bomb_is_refused() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zo = new ZipOutputStream(out)) {
                zo.putNextEntry(new ZipEntry("big.xml"));
                byte[] zeros = new byte[1024 * 1024];
                for (int i = 0; i < 61; i++) {
                    zo.write(zeros);
                }
                zo.closeEntry();
            }
            assertThat(out.size()).isLessThan(5 * 1024 * 1024);
            assertThatThrownBy(() -> AccessMatrixWorkbook.parse(out.toByteArray(), SECRET))
                    .isInstanceOf(SheetError.class).extracting("code").isEqualTo(SheetError.TOO_MUCH_DATA);
        }
    }

    static String unzipText(byte[] data) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(data))) {
            while (in.getNextEntry() != null) {
                sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }
}
