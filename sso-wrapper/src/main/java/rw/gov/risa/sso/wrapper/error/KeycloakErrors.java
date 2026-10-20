package rw.gov.risa.sso.wrapper.error;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.WebApplicationException;

/** Reads Keycloak's own error message ({@code errorMessage} / {@code error_description}) from a failure. */
public final class KeycloakErrors {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KeycloakErrors() {}

    public static int status(WebApplicationException e) {
        return e.getResponse() == null ? 0 : e.getResponse().getStatus();
    }

    @SuppressWarnings("unchecked")
    public static String message(WebApplicationException e, String fallback) {
        try {
            if (e.getResponse() != null && e.getResponse().hasEntity()) {
                String body = e.getResponse().readEntity(String.class);
                Map<String, Object> m = MAPPER.readValue(body, Map.class);
                for (String key : new String[] {"errorMessage", "error_description", "error"}) {
                    Object v = m.get(key);
                    if (v instanceof String s && !s.isBlank()) {
                        return s;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through: the body is not JSON or was already consumed
        }
        return fallback;
    }
}
