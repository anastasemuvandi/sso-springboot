package rw.gov.risa.sso.notification.web;

import java.util.Map;

/** The error envelope used by every GoRSSO service. */
public record ApiError(String error, String message, Map<String, Object> details) {
    public static ApiError of(String error, String message) {
        return new ApiError(error, message, Map.of());
    }
}
