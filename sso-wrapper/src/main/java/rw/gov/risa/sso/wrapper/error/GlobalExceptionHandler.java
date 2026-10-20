package rw.gov.risa.sso.wrapper.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every failure onto the error envelope. Keycloak failures never leak stack traces. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorEnvelope> api(ApiException e) {
        return respond(e.status(), e.error(), e.getMessage(), e.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> invalidBody(MethodArgumentNotValidException e) {
        List<Map<String, Object>> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.<String, Object>of("field", f.getField(),
                        "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.VALIDATION_ERROR, "Request validation failed",
                Map.of("fields", fields));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<ErrorEnvelope> invalidParams(Exception e) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.VALIDATION_ERROR, e.getMessage(), Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorEnvelope> unreadable(HttpMessageNotReadableException e) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.VALIDATION_ERROR, "Malformed request body",
                Map.of());
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ErrorEnvelope> denied(Exception e) {
        return respond(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "Insufficient permissions", Map.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorEnvelope> unauthenticated(AuthenticationException e) {
        return respond(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "Invalid authentication credentials",
                Map.of());
    }

    /** A Keycloak Admin API call failed (keycloak-admin-client throws JAX-RS exceptions). */
    @ExceptionHandler(WebApplicationException.class)
    public ResponseEntity<ErrorEnvelope> keycloak(WebApplicationException e) {
        int status = e.getResponse() == null ? 502 : e.getResponse().getStatus();
        return switch (status) {
            case 404 -> respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "Not found", Map.of());
            case 409 -> respond(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, "Already exists", Map.of());
            case 403 -> respond(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN,
                    "Keycloak refused the operation for this caller", Map.of());
            case 400 -> respond(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    KeycloakErrors.message(e, "Keycloak rejected the request"), Map.of());
            default -> {
                log.error("Keycloak call failed with HTTP {}", status, e);
                yield respond(HttpStatus.BAD_GATEWAY, ErrorCodes.KEYCLOAK_ERROR, "Identity provider error",
                        Map.of("upstream_status", status));
            }
        };
    }

    @ExceptionHandler(jakarta.ws.rs.ProcessingException.class)
    public ResponseEntity<ErrorEnvelope> keycloakUnreachable(jakarta.ws.rs.ProcessingException e) {
        log.error("Keycloak unreachable: {}", e.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.UPSTREAM_UNAVAILABLE,
                "Identity provider unreachable", Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorEnvelope> noRoute(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "Not found", Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> unexpected(Exception e) {
        if (e instanceof ErrorResponse er) {
            // Spring MVC's own 4xx (method not allowed, unsupported media type, ...).
            HttpStatusCode code = er.getStatusCode();
            HttpStatus status = HttpStatus.resolve(code.value());
            if (status != null && status.is4xxClientError()) {
                return respond(status, status.name().toLowerCase(), er.getBody().getDetail(), Map.of());
            }
        }
        log.error("Unhandled error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR, "Internal server error",
                Map.of());
    }

    static ResponseEntity<ErrorEnvelope> respond(HttpStatus status, String error, String message,
            Map<String, Object> details) {
        return ResponseEntity.status(status)
                .body(new ErrorEnvelope(error, message, details == null ? Map.of() : new LinkedHashMap<>(details)));
    }
}
