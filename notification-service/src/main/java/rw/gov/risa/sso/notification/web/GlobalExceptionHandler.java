package rw.gov.risa.sso.notification.web;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every error as {@code {"error","message","details"}}. Request validation failures
 * answer 422 (the status the reference FastAPI service returns for them).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> details.put(f.getField(), f.getDefaultMessage()));
        return unprocessable("Request body is invalid", details);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> invalidParams(HandlerMethodValidationException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        e.getParameterValidationResults().forEach(r -> details.put(
                r.getMethodParameter().getParameterName(),
                r.getResolvableErrors().stream().map(x -> x.getDefaultMessage()).toList()));
        return unprocessable("Request parameters are invalid", details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraint(ConstraintViolationException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        e.getConstraintViolations().forEach(v -> details.put(v.getPropertyPath().toString(), v.getMessage()));
        return unprocessable("Request parameters are invalid", details);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return unprocessable("Invalid value for '" + e.getName() + "'", Map.of(e.getName(), "invalid value"));
    }

    @ExceptionHandler(MissingRequestValueException.class)
    ResponseEntity<ApiError> missing(MissingRequestValueException e) {
        return unprocessable(e.getMessage(), Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return unprocessable("Request body is missing or not valid JSON", Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiError.of("method_not_allowed", e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("not_found", "Not found"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("internal_error", "Internal server error"));
    }

    private static ResponseEntity<ApiError> unprocessable(String message, Map<String, Object> details) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("validation_error", message, details));
    }
}
