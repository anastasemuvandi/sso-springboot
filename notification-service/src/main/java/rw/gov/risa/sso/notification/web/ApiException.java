package rw.gov.risa.sso.notification.web;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** Thrown by services/controllers; rendered as {@link ApiError}. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", message);
    }

    public static ApiException validation(String message, Map<String, Object> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", message, details);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
