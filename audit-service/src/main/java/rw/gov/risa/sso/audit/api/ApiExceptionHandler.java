package rw.gov.risa.sso.audit.api;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import rw.gov.risa.sso.audit.api.dto.ApiError;

/**
 * Maps failures onto the error envelope. Request validation answers 422 (the reference is
 * FastAPI, which uses 422 for body and query validation).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(fe -> fields.put(fe.getField(), fe.getDefaultMessage()));
        return unprocessable("Request body failed validation", Map.of("fields", fields));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> invalidParams(HandlerMethodValidationException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getParameterValidationResults().forEach(r -> fields.put(
                r.getMethodParameter().getParameterName(),
                r.getResolvableErrors().isEmpty() ? "invalid" : r.getResolvableErrors().get(0).getDefaultMessage()));
        return unprocessable("Query parameters failed validation", Map.of("fields", fields));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraint(ConstraintViolationException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getConstraintViolations().forEach(v -> fields.put(v.getPropertyPath().toString(), v.getMessage()));
        return unprocessable("Request failed validation", Map.of("fields", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        // The parser message can echo input; keep it out of the response.
        return unprocessable("Request body is not valid JSON for this endpoint", Map.of());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> badParam(Exception e) {
        String name = e instanceof MethodArgumentTypeMismatchException m ? m.getName()
                : ((MissingServletRequestParameterException) e).getParameterName();
        return unprocessable("Invalid query parameter: " + name, Map.of("fields", Map.of(name, "invalid")));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError("method_not_allowed", "Method not allowed", Map.of()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiError("unsupported_media_type", "Content-Type must be application/json", Map.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("not_found", "Not found", Map.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal_error", "Internal error", Map.of()));
    }

    private static ResponseEntity<ApiError> unprocessable(String message, Map<String, Object> details) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("validation_error", message, details));
    }
}
