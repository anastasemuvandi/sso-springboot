package rw.gov.risa.sso.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.audit.api.dto.AuditEventRead;

class ExportServiceTest {

    private final ExportService export = new ExportService(new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC));

    private static AuditEventRead event(String userId, Map<String, Object> details) {
        return new AuditEventRead(UUID.fromString("00000000-0000-0000-0000-000000000001"), "auth.login.success",
                "AUTHENTICATION", userId, null, "app", "government-internal", "minagri", "bob", userId, "10.0.0.1",
                details, "INFO", "0".repeat(64), "f".repeat(64), 2,
                OffsetDateTime.of(2026, 10, 20, 9, 0, 0, 5_000, ZoneOffset.UTC));
    }

    @Test
    void csvHasReferenceColumnsFirstAndQuotesLikePythonCsv() {
        String csv = new String(export.toCsv(List.of(event("u,1", Map.of("k", "v")))), StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).startsWith("id,created_at,event_type,category,severity,user_id,employee_id,app_id,realm,"
                + "performed_by,ip_address,details,institution,target");
        assertThat(lines[1]).contains("2026-10-20T09:00:00.000005+00:00")
                .contains("\"u,1\"")
                .contains("\"{\"\"k\"\":\"\"v\"\"}\"");
    }

    @Test
    void formulaCellsAreNeutralised() {
        assertThat(ExportService.csvCell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(ExportService.csvCell("@sum")).isEqualTo("'@sum");
        assertThat(ExportService.csvCell("plain")).isEqualTo("plain");
    }

    @Test
    void pdfsAreRealPdfs() {
        byte[] pdf = export.toPdf(List.of(event("u1", Map.of("long", "x".repeat(200)))), "t", Map.of("category", "A"));
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        byte[] compliance = export.compliancePdf(List.of(event("u1", Map.of())), null, null);
        assertThat(new String(compliance, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void emptyExportsStillRender() {
        assertThat(export.toPdf(List.of(), "t", null)).isNotEmpty();
        assertThat(new String(export.toCsv(List.of()), StandardCharsets.UTF_8)).endsWith("event_hash\r\n");
    }
}
