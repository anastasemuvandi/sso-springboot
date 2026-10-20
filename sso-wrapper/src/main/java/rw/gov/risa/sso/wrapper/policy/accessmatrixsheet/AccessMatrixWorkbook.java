package rw.gov.risa.sso.wrapper.policy.accessmatrixsheet;

import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixSheet.EXPORT_MAX_AGE;
import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixSheet.FORMAT;
import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixSheet.H_EMPLOYEE;
import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixSheet.IDENTITY_HEADERS;
import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixSheet.MARK;
import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixSheet.SHEET_ACCESS;
import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixSheet.SHEET_HELP;
import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixSheet.SHEET_META;
import static rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixSheet.VERSION;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Writing and reading the access-matrix .xlsx with Apache POI. Bytes in, bytes out.
 *
 * <p>Export: a protected {@code Access} sheet (identity columns locked, the cells of a view-only
 * column or a blocked row locked, a {@code YES} dropdown on editable columns), every text cell
 * written as a STRING (a value like {@code =HYPERLINK(...)} can never become a formula), NIDs never
 * exported (only "National ID on file: yes/no"), an {@code Instructions} sheet, and a veryHidden,
 * protected {@code _meta} sheet with the HMAC signature, institution and export date.
 *
 * <p>Import: {@link #parse} verifies the frame and collects per-row/per-cell problems; a file that is
 * not an export throws {@link SheetError}.
 */
public final class AccessMatrixWorkbook {

    private static final String AUTHOR = "GoR SSO";
    private static final ObjectMapper JSON = new ObjectMapper();

    private AccessMatrixWorkbook() {
    }

    // ---- writing ---------------------------------------------------------------------

    /** The export, as .xlsx bytes. */
    public static byte[] build(SheetMeta meta, List<SheetColumn> columns, List<SheetRow> rows, String secret) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet ws = wb.createSheet(SHEET_ACCESS);
            Styles st = new Styles(wb);

            List<String> headers = new ArrayList<>(IDENTITY_HEADERS);
            columns.forEach(c -> headers.add(c.clientId()));
            XSSFRow head = ws.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                XSSFCell c = head.createCell(i);
                putText(c, headers.get(i));
                c.setCellStyle(st.header);
            }
            int firstApp = IDENTITY_HEADERS.size(); // 0-based
            XSSFDrawing drawing = ws.createDrawingPatriarch();
            for (int k = 0; k < columns.size(); k++) {
                SheetColumn col = columns.get(k);
                String note = col.name() != null && !col.name().isEmpty() ? col.name() : col.clientId();
                if (!col.editable()) {
                    note += " — view only for your role: this column cannot be imported.";
                }
                comment(wb, drawing, head.getCell(firstApp + k), note);
            }

            for (int r = 0; r < rows.size(); r++) {
                SheetRow row = rows.get(r);
                XSSFRow xr = ws.createRow(r + 1);
                List<String> identity = new ArrayList<>();
                identity.add(row.employeeNumber());
                identity.add(row.firstName());
                identity.add(row.lastName());
                identity.add(row.email());
                identity.add(row.status());
                identity.add(row.account() == null ? null
                        : AccessMatrixSheet.ACCOUNT_LABELS.getOrDefault(row.account(), row.account().code()));
                identity.add(row.hasNid() ? "yes" : "no");
                for (int c = 0; c < identity.size(); c++) {
                    XSSFCell cell = xr.createCell(c);
                    putText(cell, identity.get(c));
                    cell.setCellStyle(st.identity);
                }
                for (int k = 0; k < columns.size(); k++) {
                    SheetColumn col = columns.get(k);
                    XSSFCell cell = xr.createCell(firstApp + k);
                    if (Boolean.TRUE.equals(row.cells().get(col.clientId()))) {
                        putText(cell, MARK);
                    }
                    // Editable only where a grant could happen: a manageable column and a row nothing
                    // blocks. An existing YES stays editable so clearing it is possible (and reported).
                    boolean open = col.editable() && row.lockedReason() == null;
                    cell.setCellStyle(open ? st.open : st.locked);
                }
                if (row.lockedReason() != null) {
                    comment(wb, drawing, xr.getCell(0), row.lockedReason());
                }
            }

            int lastRow = Math.max(2, rows.size() + 1); // 1-based
            XSSFDataValidationHelper dvh = new XSSFDataValidationHelper(ws);
            for (int k = 0; k < columns.size(); k++) {
                if (!columns.get(k).editable()) {
                    continue;
                }
                DataValidationConstraint constraint = dvh.createExplicitListConstraint(new String[] {MARK});
                CellRangeAddressList range = new CellRangeAddressList(1, lastRow - 1, firstApp + k, firstApp + k);
                DataValidation dv = dvh.createValidation(constraint, range);
                dv.setEmptyCellAllowed(true);
                dv.setShowErrorBox(true);
                dv.setErrorStyle(DataValidation.ErrorStyle.STOP);
                dv.createErrorBox("YES or empty", "Type YES to give this person the application, or leave it empty.");
                ws.addValidationData(dv);
            }

            int[] widths = {16, 16, 18, 30, 13, 22, 12};
            for (int i = 0; i < widths.length; i++) {
                ws.setColumnWidth(i, widths[i] * 256);
            }
            for (int k = 0; k < columns.size(); k++) {
                int w = Math.max(12, Math.min(28, columns.get(k).clientId().length() + 2));
                ws.setColumnWidth(firstApp + k, w * 256);
            }
            ws.createFreezePane(firstApp, 1);
            ws.setAutoFilter(new CellRangeAddress(0, lastRow - 1, 0, headers.size() - 1));
            ws.enableLocking();
            // Filtering and sorting stay available; protection only guards against slips.
            ws.lockAutoFilter(false);
            ws.lockSort(false);
            ws.lockFormatColumns(false);

            XSSFSheet help = wb.createSheet(SHEET_HELP);
            List<String> lines = new ArrayList<>(List.of(
                    "Access matrix for " + meta.institution(),
                    "Exported " + meta.exportedAt() + " by " + meta.exportedBy() + ". Export id " + meta.exportId() + ".",
                    "",
                    "How to use this file",
                    "1. On the Access sheet, type YES (or pick it from the dropdown) in an application's column "
                            + "for each person who should be given that application.",
                    "2. Do not add, rename or reorder columns, and do not change employee numbers: rows are "
                            + "matched by employee number only.",
                    "3. Clearing an existing YES does NOT remove access. Imports only grant. Removing access is "
                            + "done in the access matrix itself.",
                    "4. Grey cells cannot be imported: an application your role may only view, or a person who "
                            + "cannot be given access (a leaver, or an account that needs correcting first).",
                    "5. Return the file to an administrator, who imports it, checks the preview, and confirms.",
                    "6. The file must be imported within " + EXPORT_MAX_AGE.toDays() + " days of export.",
                    "",
                    "Applications in this file"));
            for (SheetColumn c : columns) {
                lines.add(c.clientId() + " — " + (c.name() != null && !c.name().isEmpty() ? c.name() : c.clientId())
                        + (c.editable() ? "" : " (view only)"));
            }
            for (int i = 0; i < lines.size(); i++) {
                putText(help.createRow(i).createCell(0), lines.get(i));
            }
            help.getRow(0).getCell(0).setCellStyle(st.title);
            help.getRow(3).getCell(0).setCellStyle(st.bold);
            help.setColumnWidth(0, 120 * 256);

            XSSFSheet metaWs = wb.createSheet(SHEET_META);
            String[][] entries = {
                {"format", FORMAT},
                {"version", Integer.toString(VERSION)},
                {"institution", meta.institution()},
                {"clients", SheetMeta.Json.list(meta.clients())},
                {"export_id", meta.exportId()},
                {"exported_at", meta.exportedAt()},
                {"exported_by", meta.exportedBy()},
                {"signature", AccessMatrixSheet.sign(meta, secret)},
            };
            for (int i = 0; i < entries.length; i++) {
                XSSFRow mr = metaWs.createRow(i);
                putText(mr.createCell(0), entries[i][0]);
                putText(mr.createCell(1), entries[i][1]);
            }
            metaWs.enableLocking();
            wb.setSheetVisibility(wb.getSheetIndex(metaWs), SheetVisibility.VERY_HIDDEN);

            wb.setActiveSheet(0);
            wb.setSelectedTab(0);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not write the workbook", e);
        }
    }

    /**
     * Write a value as TEXT, never a formula: {@code setCellValue(String)} always produces a string
     * cell, so an IPPIS name like {@code =HYPERLINK(...)} stays a literal. Null/empty = blank.
     */
    static void putText(XSSFCell cell, String value) {
        if (value == null || value.isEmpty()) {
            cell.setBlank();
            return;
        }
        cell.setCellValue(value);
    }

    private static void comment(XSSFWorkbook wb, XSSFDrawing drawing, XSSFCell cell, String text) {
        ClientAnchor anchor = wb.getCreationHelper().createClientAnchor();
        anchor.setCol1(cell.getColumnIndex());
        anchor.setCol2(cell.getColumnIndex() + 3);
        anchor.setRow1(cell.getRowIndex());
        anchor.setRow2(cell.getRowIndex() + 4);
        XSSFComment c = drawing.createCellComment(anchor);
        c.setString(new XSSFRichTextString(text));
        c.setAuthor(AUTHOR);
        cell.setCellComment(c);
    }

    private static final class Styles {
        final XSSFCellStyle header;
        final XSSFCellStyle identity;
        final XSSFCellStyle open;
        final XSSFCellStyle locked;
        final XSSFCellStyle title;
        final XSSFCellStyle bold;

        Styles(XSSFWorkbook wb) {
            XSSFFont boldFont = wb.createFont();
            boldFont.setBold(true);
            XSSFFont titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 13);

            header = wb.createCellStyle();
            header.setFont(boldFont);
            fill(header, "DDE7F3");
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setLocked(true);

            identity = wb.createCellStyle();
            identity.setLocked(true);

            open = wb.createCellStyle();
            open.setLocked(false);
            fill(open, "FFFFFF");
            open.setAlignment(HorizontalAlignment.CENTER);
            open.setBorderBottom(BorderStyle.NONE);

            locked = wb.createCellStyle();
            locked.setLocked(true);
            fill(locked, "EEEEEE");
            locked.setAlignment(HorizontalAlignment.CENTER);

            title = wb.createCellStyle();
            title.setFont(titleFont);
            bold = wb.createCellStyle();
            bold.setFont(boldFont);
        }

        private static void fill(XSSFCellStyle s, String hex) {
            s.setFillForegroundColor(new XSSFColor(java.util.HexFormat.of().parseHex(hex), null));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
    }

    // ---- reading ---------------------------------------------------------------------

    private static SheetError notOurs() {
        return new SheetError(SheetError.NOT_EXPORTED_HERE,
                "This file was not exported from the access matrix, or its export details "
                        + "were changed. Export the matrix again and fill in that file.");
    }

    /**
     * Read an exported-then-edited file. Throws {@link SheetError} for a file that is not one
     * (empty, too large, not a zip/.xlsx, macros, zip bomb, unreadable, no/forged/unsigned
     * {@code _meta}, wrong version, no Access sheet, no employee column, too many rows); per-row and
     * per-cell trouble is collected in {@link ParsedSheet#problems()} instead.
     */
    public static ParsedSheet parse(byte[] data, String secret) {
        if (data == null || data.length == 0) {
            throw new SheetError(SheetError.EMPTY_FILE, "The file is empty.");
        }
        if (data.length > AccessMatrixSheet.MAX_FILE_BYTES) {
            throw new SheetError(SheetError.TOO_LARGE,
                    "The file is larger than " + AccessMatrixSheet.MAX_FILE_BYTES / (1024 * 1024) + " MB.");
        }
        inspectZip(data);

        XSSFWorkbook wb;
        try {
            wb = new XSSFWorkbook(new ByteArrayInputStream(data));
        } catch (Exception | LinkageError e) {
            throw new SheetError(SheetError.UNREADABLE,
                    "The workbook could not be read (" + e.getClass().getSimpleName() + ").", e);
        }
        try (wb) {
            SheetMeta meta = readMeta(wb, secret);
            XSSFSheet access = wb.getSheet(SHEET_ACCESS);
            if (access == null) {
                throw new SheetError(SheetError.ACCESS_SHEET_MISSING, "The '" + SHEET_ACCESS + "' sheet is missing.");
            }
            return readAccess(access, meta);
        } catch (IOException e) {
            throw new SheetError(SheetError.UNREADABLE, "The workbook could not be read (IOException).", e);
        }
    }

    /** Not a zip -> not .xlsx; a VBA project -> macros; more than 60 MB inflated -> refused. */
    private static void inspectZip(byte[] data) {
        long total = 0;
        boolean any = false;
        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                any = true;
                if (e.getName().toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) {
                    throw new SheetError(SheetError.MACROS,
                            "Files with macros are not accepted. Save it as a plain .xlsx workbook.");
                }
                int n;
                while ((n = z.read(buf)) > 0) {
                    total += n;
                    if (total > AccessMatrixSheet.MAX_UNCOMPRESSED_BYTES) {
                        throw new SheetError(SheetError.TOO_MUCH_DATA,
                                "The file expands to more data than an access-matrix export can contain.");
                    }
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new SheetError(SheetError.NOT_XLSX,
                    "This is not an Excel .xlsx file. Export the matrix and edit that file.", ex);
        }
        if (!any) {
            throw new SheetError(SheetError.NOT_XLSX,
                    "This is not an Excel .xlsx file. Export the matrix and edit that file.");
        }
    }

    private static SheetMeta readMeta(XSSFWorkbook wb, String secret) {
        XSSFSheet ws = wb.getSheet(SHEET_META);
        if (ws == null) {
            throw notOurs();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int r = 0; r < 50; r++) {
            XSSFRow row = ws.getRow(r);
            if (row == null) {
                continue;
            }
            Object k = value(row.getCell(0));
            if (k != null) {
                Object v = value(row.getCell(1));
                values.put(AccessMatrixSheet.pyStr(k), v == null ? "" : AccessMatrixSheet.pyStr(v));
            }
        }
        if (!FORMAT.equals(values.get("format"))) {
            throw notOurs();
        }
        if (!Integer.toString(VERSION).equals(values.get("version"))) {
            throw new SheetError(SheetError.WRONG_VERSION,
                    "This export is from a different version of the console. Export it again.");
        }
        List<String> clients = new ArrayList<>();
        String rawClients = values.get("clients");
        try {
            JsonNode node = JSON.readTree(rawClients == null || rawClients.isEmpty() ? "[]" : rawClients);
            if (node == null || !node.isArray()) {
                throw notOurs();
            }
            for (JsonNode n : node) {
                clients.add(n.isTextual() ? n.asText() : n.toString());
            }
        } catch (IOException e) {
            throw notOurs();
        }
        SheetMeta meta = new SheetMeta(
                values.getOrDefault("institution", ""), clients, values.getOrDefault("export_id", ""),
                values.getOrDefault("exported_at", ""), values.getOrDefault("exported_by", ""));
        if (meta.institution().isEmpty() || clients.isEmpty()
                || !AccessMatrixSheet.verify(meta, values.getOrDefault("signature", ""), secret)) {
            throw notOurs();
        }
        return meta;
    }

    private static ParsedSheet readAccess(XSSFSheet ws, SheetMeta meta) {
        if (ws.getPhysicalNumberOfRows() == 0) {
            throw new SheetError(SheetError.ACCESS_SHEET_EMPTY, "The Access sheet is empty.");
        }
        List<String> header = new ArrayList<>();
        XSSFRow h = ws.getRow(0);
        int width = maxWidth(ws);
        for (int i = 0; i < width; i++) {
            header.add(AccessMatrixSheet.text(h == null ? null : value(h.getCell(i))));
        }
        int numberAt = header.indexOf(H_EMPLOYEE);
        if (numberAt < 0) {
            throw new SheetError(SheetError.EMPLOYEE_COLUMN_MISSING, "The '" + H_EMPLOYEE + "' column is missing.");
        }

        List<Problem> problems = new ArrayList<>();
        Map<String, Integer> clientAt = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String hd = header.get(i);
            if (hd.isEmpty() || IDENTITY_HEADERS.contains(hd)) {
                continue;
            }
            if (meta.clients().contains(hd) && !clientAt.containsKey(hd)) {
                clientAt.put(hd, i);
            } else {
                problems.add(new Problem(AccessMatrixSheet.REASON_UNKNOWN_COLUMN, 1, null, null, hd));
            }
        }
        List<String> clients = new ArrayList<>();
        for (String c : meta.clients()) {
            if (clientAt.containsKey(c)) {
                clients.add(c);
            } else {
                problems.add(new Problem(AccessMatrixSheet.REASON_MISSING_COLUMN, 1, null, c, null));
            }
        }

        Map<String, Map<String, Boolean>> marks = new LinkedHashMap<>();
        Map<String, Integer> rowsByNumber = new LinkedHashMap<>();
        int last = ws.getLastRowNum(); // 0-based
        for (int idx = 1; idx <= last; idx++) {
            int r = idx + 1; // spreadsheet row
            if (r > AccessMatrixSheet.MAX_ROWS + 1) {
                throw new SheetError(SheetError.TOO_MANY_ROWS,
                        "The file has more than " + AccessMatrixSheet.MAX_ROWS + " rows.");
            }
            XSSFRow row = ws.getRow(idx);
            String number = AccessMatrixSheet.text(row == null ? null : value(row.getCell(numberAt)));
            Map<String, Boolean> rowMarks = new LinkedHashMap<>();
            List<Problem> bad = new ArrayList<>();
            for (Map.Entry<String, Integer> ca : clientAt.entrySet()) {
                Object v = row == null ? null : value(row.getCell(ca.getValue()));
                Boolean mark = AccessMatrixSheet.readMark(v);
                if (mark == null) {
                    String shown = AccessMatrixSheet.text(v);
                    bad.add(new Problem(AccessMatrixSheet.REASON_INVALID_VALUE, r, number.isEmpty() ? null : number,
                            ca.getKey(), shown.length() > 60 ? shown.substring(0, 60) : shown));
                } else {
                    rowMarks.put(ca.getKey(), mark);
                }
            }
            if (number.isEmpty()) {
                if (rowMarks.containsValue(Boolean.TRUE) || !bad.isEmpty()) {
                    problems.add(new Problem(AccessMatrixSheet.REASON_NO_EMPLOYEE_NUMBER, r, null, null, null));
                }
                continue;
            }
            if (marks.containsKey(number)) {
                problems.add(new Problem(AccessMatrixSheet.REASON_DUPLICATE_ROW, r, number, null, null));
                continue;
            }
            marks.put(number, rowMarks);
            rowsByNumber.put(number, r);
            problems.addAll(bad);
        }
        return new ParsedSheet(meta, List.copyOf(clients), marks, rowsByNumber, problems);
    }

    private static int maxWidth(XSSFSheet ws) {
        int w = 0;
        for (int i = 0; i <= ws.getLastRowNum(); i++) {
            XSSFRow r = ws.getRow(i);
            if (r != null) {
                w = Math.max(w, r.getLastCellNum());
            }
        }
        return w;
    }

    /**
     * A cell's value the way openpyxl's {@code data_only=True} returns it: String, Double, Boolean,
     * the cached result of a formula, an error's text, or null for blank/missing.
     */
    static Object value(XSSFCell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        return switch (type) {
            case STRING -> {
                String s = cell.getStringCellValue();
                yield s == null || s.isEmpty() ? null : s;
            }
            case NUMERIC -> cell.getNumericCellValue();
            case BOOLEAN -> cell.getBooleanCellValue();
            case ERROR -> cell.getErrorCellString();
            default -> null;
        };
    }

    /** For callers holding a stream. */
    public static ParsedSheet parse(InputStream in, String secret) throws IOException {
        return parse(in.readNBytes(AccessMatrixSheet.MAX_FILE_BYTES + 1), secret);
    }

    /** Column letter of a 0-based index (A, B, ...). */
    static String letter(int index) {
        return CellReference.convertNumToColString(index);
    }
}
