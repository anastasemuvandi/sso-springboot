package rw.gov.risa.sso.audit.api.dto;

import java.util.List;

/** {@code GET /audit/events}: {@code {total, limit, offset, items}}. */
public record EventPage(long total, int limit, int offset, List<AuditEventRead> items) {
}
