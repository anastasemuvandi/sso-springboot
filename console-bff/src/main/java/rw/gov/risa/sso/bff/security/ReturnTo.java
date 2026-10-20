package rw.gov.risa.sso.bff.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * The post-login destination. Only a same-origin, absolute PATH is accepted ({@code /clients/x?y=1});
 * anything that could leave the origin ({@code //evil}, {@code /\evil}, a scheme, control characters)
 * or loop back into the auth endpoints is dropped, so {@code /bff/login} is never an open redirect.
 */
public final class ReturnTo {
    public static final String SESSION_ATTRIBUTE = ReturnTo.class.getName() + ".TARGET";
    private static final int MAX_LENGTH = 2048;

    private ReturnTo() {
    }

    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_LENGTH) {
            return null;
        }
        if (!raw.startsWith("/") || raw.startsWith("//") || raw.indexOf('\\') >= 0) {
            return null;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return null;
            }
        }
        String path = raw.toLowerCase(java.util.Locale.ROOT);
        for (String prefix : new String[] {"/bff/", "/oauth2/", "/login/", "/api/", "/actuator"}) {
            if (path.startsWith(prefix)) {
                return null;
            }
        }
        return raw;
    }

    public static void remember(HttpServletRequest request, String target) {
        if (target != null) {
            request.getSession(true).setAttribute(SESSION_ATTRIBUTE, target);
        }
    }

    public static String pop(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object target = session.getAttribute(SESSION_ATTRIBUTE);
        session.removeAttribute(SESSION_ATTRIBUTE);
        return target instanceof String s ? sanitize(s) : null;
    }
}
