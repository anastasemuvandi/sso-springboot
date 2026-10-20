package rw.gov.risa.sso.ippismock.api;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** Maps to the error envelope {@code {"error", "message", "details"}}. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, Object> details() { return details; }

    public static ApiException notFound(String employeeNumber) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found",
                "no IPPIS record for employee_number '" + employeeNumber + "'");
    }

    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", message);
    }
}
