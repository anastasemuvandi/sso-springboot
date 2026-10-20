package rw.gov.risa.sso.wrapper.policy.overview;

/**
 * A Keycloak event, reduced: {@code type} ({@code LOGIN} / {@code LOGIN_ERROR} / other),
 * {@code userId} (null for an unknown username), {@code timeMs} epoch millis, {@code error} (null
 * when none).
 */
public record LoginEvent(String type, String userId, long timeMs, String error) {
}
