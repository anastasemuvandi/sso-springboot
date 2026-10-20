package rw.gov.risa.sso.starter.web;

import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import rw.gov.risa.sso.starter.joinkey.JoinKeyMissingException;

/**
 * Renders starter exceptions in the platform error envelope
 * {@code {"error": "...", "message": "...", "details": {}}}. Lowest precedence, so an app's own
 * advice for the same exception wins.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GorssoErrorAdvice {

  @ExceptionHandler(JoinKeyMissingException.class)
  public ResponseEntity<Map<String, Object>> joinKeyMissing(JoinKeyMissingException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            Map.of(
                "error", JoinKeyMissingException.ERROR_CODE,
                "message", e.getMessage(),
                "details", Map.of()));
  }
}
