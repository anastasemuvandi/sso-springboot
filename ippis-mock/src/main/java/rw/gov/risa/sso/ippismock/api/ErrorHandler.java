package rw.gov.risa.sso.ippismock.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ErrorHandler {

    public static Map<String, Object> envelope(String code, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        body.put("details", details == null ? Map.of() : details);
        return body;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(envelope(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(envelope("validation_error", "request body is not valid JSON for this endpoint", null));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<Map<String, Object>> badParam(Exception e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(envelope("validation_error", e.getMessage(), null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> noRoute(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(envelope("not_found", "no such endpoint", null));
    }
}
