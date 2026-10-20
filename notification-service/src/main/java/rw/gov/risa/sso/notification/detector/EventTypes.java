package rw.gov.risa.sso.notification.detector;

import java.util.Set;

/**
 * Event types the rules react to. Both the audit service's canonical names and the
 * wrapper's legacy names are accepted, exactly as the reference detector does.
 */
public final class EventTypes {
    public static final String AUTH_LOGIN_SUCCESS = "auth.login.success";
    public static final String AUTH_LOGIN_FAILED = "auth.login.failed";

    public static final Set<String> LOGIN_SUCCESS = Set.of(AUTH_LOGIN_SUCCESS, "login_success");
    public static final Set<String> LOGIN_FAILED = Set.of(AUTH_LOGIN_FAILED, "login_failure");

    private EventTypes() {
    }
}
