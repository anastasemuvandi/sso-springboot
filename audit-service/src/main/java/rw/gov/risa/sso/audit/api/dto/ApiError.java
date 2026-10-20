package rw.gov.risa.sso.audit.api.dto;

import java.util.Map;

/** The platform error envelope: {@code {"error": "<machine_code>", "message": "...", "details": {...}}}. */
public record ApiError(String error, String message, Map<String, Object> details) {
}
