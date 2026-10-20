package rw.gov.risa.sso.audit.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import rw.gov.risa.sso.audit.api.dto.AuditEventRead;
import rw.gov.risa.sso.audit.chain.HashChain;

/**
 * CSV and PDF renderings (reference core/export.py). Pure formatting, no I/O beyond the
 * returned bytes.
 *
 * <p>CSV: the reference's columns in the reference's order, then {@code institution},
 * {@code target}, {@code hash_version}, {@code prev_hash}, {@code event_hash} appended; RFC 4180
 * quoting with CRLF line ends (Python's csv "excel" dialect). Two deliberate differences:
 * {@code details} is JSON rather than a Python dict repr, and cells that a spreadsheet would
 * execute as a formula ({@code = + - @}, tab, CR) are prefixed with {@code '}.
 */
@Service
public class ExportService {

    static final List<String> CSV_FIELDS = List.of("id", "created_at", "event_type", "category", "severity",
            "user_id", "employee_id", "app_id", "realm", "performed_by", "ip_address", "details",
            "institution", "target", "hash_version", "prev_hash", "event_hash");

    private static final Color HEADER = new Color(0x00, 0x33, 0x66);
    private static final Color STRIPE = new Color(0xf0, 0xf4, 0xf8);
    private static final Color GRID = new Color(0xcc, 0xcc, 0xcc);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ObjectMapper mapper;
    private final Clock clock;

    public ExportService(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public String stamp() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).format(FILE_STAMP);
    }

    // ------------------------------------------------------------------ CSV

    public byte[] toCsv(List<AuditEventRead> events) {
        StringBuilder sb = new StringBuilder();
        row(sb, CSV_FIELDS);
        for (AuditEventRead e : events) {
            row(sb, List.of(
                    e.id().toString(),
                    e.createdAt() == null ? "" : HashChain.pyIsoFormat(e.createdAt()),
                    nz(e.eventType()), nz(e.category()), nz(e.severity()), nz(e.userId()), nz(e.employeeId()),
                    nz(e.appId()), nz(e.realm()), nz(e.performedBy()), nz(e.ipAddress()), json(e.details()),
                    nz(e.institution()), nz(e.target()), Integer.toString(e.hashVersion()), nz(e.prevHash()),
                    nz(e.eventHash())));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void row(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvCell(cells.get(i)));
        }
        sb.append("\r\n");
    }

    static String csvCell(String raw) {
        String v = raw == null ? "" : raw;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    // ------------------------------------------------------------------ PDF

    public byte[] toPdf(List<AuditEventRead> events, String title, Map<String, String> filters) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 42, 42);
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph(title, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16)));
        StringBuilder meta = new StringBuilder("Generated: ")
                .append(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).format(MINUTE)).append(" UTC");
        StringBuilder f = new StringBuilder();
        if (filters != null) {
            filters.forEach((k, v) -> {
                if (v != null && !v.isEmpty()) {
                    f.append(f.isEmpty() ? "" : "  |  ").append(k).append(": ").append(v);
                }
            });
        }
        if (!f.isEmpty()) {
            meta.append("  |  Filters: ").append(f);
        }
        doc.add(spaced(new Paragraph(meta.toString(), FontFactory.getFont(FontFactory.HELVETICA, 9))));

        PdfPTable table = table(new float[] {3.8f, 4.5f, 3.2f, 2.2f, 3.5f, 2.8f, 2.8f, 5.5f},
                List.of("Timestamp", "Event Type", "Category", "Severity", "User", "App", "IP", "Details"));
        int i = 0;
        for (AuditEventRead e : events) {
            String detail = json(e.details());
            if (detail.length() > 60) {
                detail = detail.substring(0, 57) + "...";
            }
            body(table, i++, 7, minute(e.createdAt()), e.eventType(), e.category(), e.severity(), e.userId(),
                    e.appId(), e.ipAddress(), detail);
        }
        doc.add(table);
        doc.add(spaced(new Paragraph("Total events: " + events.size(), FontFactory.getFont(FontFactory.HELVETICA, 9))));
        doc.close();
        return out.toByteArray();
    }

    /** Reference compliance_report_pdf: summary counts, then up to 500 detail rows. */
    public byte[] compliancePdf(List<AuditEventRead> events, OffsetDateTime from, OffsetDateTime to) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 56, 56, 56, 56);
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph("Government of Rwanda — SSO Compliance Report",
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16)));
        String period = (from == null ? "all" : from.toLocalDate().toString()) + " to "
                + (to == null ? "now" : to.toLocalDate().toString());
        doc.add(spaced(new Paragraph("Period: " + period, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12))));

        long ok = events.stream().filter(e -> "auth.login.success".equals(e.eventType())).count();
        long fail = events.stream().filter(e -> "auth.login.failed".equals(e.eventType())).count();
        long denied = events.stream().filter(e -> "authz.access.denied".equals(e.eventType())).count();
        long offboards = events.stream().filter(e -> "lifecycle.offboard".equals(e.eventType())).count();
        long admin = events.stream().filter(e -> "ADMIN".equals(e.category())).count();

        PdfPTable summary = table(new float[] {10, 5}, List.of("Metric", "Count"));
        Object[][] rows = {
            {"Successful logins", ok}, {"Failed logins", fail}, {"Access denials", denied},
            {"Employee offboards", offboards}, {"Admin actions", admin}, {"Total events", (long) events.size()}};
        int i = 0;
        for (Object[] r : rows) {
            body(summary, i++, 9, (String) r[0], String.valueOf(r[1]));
        }
        doc.add(summary);
        doc.add(spaced(new Paragraph("Event Detail", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14))));

        PdfPTable detail = table(new float[] {3.5f, 6, 4, 4}, List.of("Timestamp", "Event", "User", "App / IP"));
        i = 0;
        for (AuditEventRead e : events.subList(0, Math.min(500, events.size()))) {
            body(detail, i++, 7, minute(e.createdAt()), e.eventType(), e.userId(),
                    nz(e.appId()) + " / " + nz(e.ipAddress()));
        }
        doc.add(detail);
        doc.close();
        return out.toByteArray();
    }

    private static Paragraph spaced(Paragraph p) {
        p.setSpacingBefore(6);
        p.setSpacingAfter(10);
        return p;
    }

    private static PdfPTable table(float[] widths, List<String> headers) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        Font hf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
        for (String h : headers) {
            PdfPCell c = new PdfPCell(new Phrase(h, hf));
            c.setBackgroundColor(HEADER);
            style(c);
            t.addCell(c);
        }
        return t;
    }

    private static void body(PdfPTable t, int rowIndex, float size, String... cells) {
        Font bf = FontFactory.getFont(FontFactory.HELVETICA, size);
        for (String s : cells) {
            PdfPCell c = new PdfPCell(new Phrase(nz(s), bf));
            c.setBackgroundColor(rowIndex % 2 == 0 ? Color.WHITE : STRIPE);
            style(c);
            t.addCell(c);
        }
    }

    private static void style(PdfPCell c) {
        c.setBorderColor(GRID);
        c.setBorderWidth(0.3f);
        c.setBorder(Rectangle.BOX);
        c.setVerticalAlignment(Element.ALIGN_TOP);
        c.setPadding(3);
    }

    private static String minute(OffsetDateTime t) {
        return t == null ? "" : t.withOffsetSameInstant(ZoneOffset.UTC).format(MINUTE);
    }

    private String json(Map<String, Object> details) {
        try {
            return mapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
