package rw.gov.risa.sso.audit.api.dto;

/** {@code POST /audit/retention/apply}: {@code {"deleted": n, "message": "..."}}. */
public record RetentionRunResult(long deleted, String message) {
}
