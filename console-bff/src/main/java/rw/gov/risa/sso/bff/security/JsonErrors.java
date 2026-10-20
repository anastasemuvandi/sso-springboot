package rw.gov.risa.sso.bff.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

/** The error envelope {@code {"error", "message", "details"}}, written straight to a servlet response. */
public final class JsonErrors {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonErrors() {
    }

    public static Map<String, Object> envelope(String code, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        body.put("details", details == null ? Map.of() : details);
        return body;
    }

    public static void write(HttpServletResponse response, int status, String code, String message,
            Map<String, Object> details) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        MAPPER.writeValue(response.getOutputStream(), envelope(code, message, details));
    }

    public static void unauthenticated(HttpServletResponse response, String code, String message) throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, code, message,
                Map.of("authenticated", false, "login_url", "/bff/login"));
    }
}
