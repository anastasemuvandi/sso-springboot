package rw.gov.risa.sso.wrapper.error;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * The one exception type services throw to produce the error envelope
 * {@code {"error": "<machine_code>", "message": "...", "details": {...}}}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String error, String message) {
        this(status, error, message, Map.of());
    }

    public ApiException(HttpStatus status, String error, String message, Map<String, ?> details) {
        super(message);
        this.status = status;
        this.error = error;
        this.details = details == null ? Map.of() : Map.copyOf(withoutNulls(details));
    }

    public HttpStatus status() {
        return status;
    }

    public String error() {
        return error;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException badRequest(String error, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, error, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String error, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, error, message);
    }

    public static ApiException forbidden(String error, String message, Map<String, ?> details) {
        return new ApiException(HttpStatus.FORBIDDEN, error, message, details);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, message);
    }

    public static ApiException conflict(String error, String message, Map<String, ?> details) {
        return new ApiException(HttpStatus.CONFLICT, error, message, details);
    }

    public static ApiException unavailable(String error, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, error, message);
    }

    public static ApiException badGateway(String error, String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, error, message);
    }

    private static Map<String, Object> withoutNulls(Map<String, ?> in) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        in.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k, v);
            }
        });
        return out;
    }
}
