package rw.gov.risa.sso.notification.detector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Titles and one-line messages, worded as the reference alerter/channels word them. */
public final class AlertText {

    private AlertText() {
    }

    /** Python {@code rule.replace('_', ' ').title()}: "brute_force" → "Brute Force". */
    public static String humanize(String rule) {
        String s = rule.replace('_', ' ');
        StringBuilder sb = new StringBuilder(s.length());
        boolean prevLetter = false;
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c)) {
                sb.append(prevLetter ? Character.toLowerCase(c) : Character.toUpperCase(c));
                prevLetter = true;
            } else {
                sb.append(c);
                prevLetter = false;
            }
        }
        return sb.toString();
    }

    /** Notification title: {@code [CRITICAL] SSO Alert — Brute Force}. */
    public static String title(String severity, String rule) {
        return "[" + severity + "] SSO Alert — " + humanize(rule);
    }

    /** Email subject: {@code [CRITICAL] SSO Security Alert — Brute Force}. */
    public static String emailSubject(String severity, String rule) {
        return "[" + severity + "] SSO Security Alert — " + humanize(rule);
    }

    /** Reference {@code _format_message}: "User: u; IP: i; App: a; k: v; ..." or the rule name. */
    public static String message(String rule, String userId, String ip, String appId, Map<String, Object> details) {
        List<String> parts = new ArrayList<>();
        if (userId != null) {
            parts.add("User: " + userId);
        }
        if (ip != null) {
            parts.add("IP: " + ip);
        }
        if (appId != null) {
            parts.add("App: " + appId);
        }
        if (details != null) {
            details.forEach((k, v) -> parts.add(k + ": " + v));
        }
        return parts.isEmpty() ? rule : String.join("; ", parts);
    }
}
