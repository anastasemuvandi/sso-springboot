package rw.gov.risa.sso.wrapper.error;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Wire shape of every error response. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ErrorEnvelope(String error, String message, Map<String, Object> details) {
    public static ErrorEnvelope of(String error, String message) {
        return new ErrorEnvelope(error, message, Map.of());
    }
}
