package rw.gov.risa.sso.audit.api.dto;

/** {@code GET /health}: {@code {"status": "ok", "service": "audit-service"}}. */
public record ServiceStatus(String status, String service) {
}
