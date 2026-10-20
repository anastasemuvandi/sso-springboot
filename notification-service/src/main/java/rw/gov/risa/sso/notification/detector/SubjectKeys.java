package rw.gov.risa.sso.notification.detector;

/**
 * Cooldown subject keys. The reference de-duplicated on {@code user_id} (falling
 * back to {@code ip_address}); that made an IP-cluster brute-force alert collide with
 * whichever user happened to be last in the cluster, and made a user's second
 * first-time app access within a day disappear. The key names the actual subject:
 * <ul>
 *   <li>user-cluster rules: {@code user:<id>}</li>
 *   <li>IP-cluster brute force: {@code ip:<addr>}</li>
 *   <li>anomalous access: {@code user:<id>|app:<app>} (the reference's own comment:
 *       "only alert once per day per user+app pair")</li>
 * </ul>
 */
public final class SubjectKeys {
    private SubjectKeys() {
    }

    public static String user(String userId) {
        return "user:" + userId;
    }

    public static String ip(String ip) {
        return "ip:" + ip;
    }

    public static String userApp(String userId, String appId) {
        return "user:" + userId + "|app:" + appId;
    }

    /** Fallback used when a result carries neither (never produced by the built-in rules). */
    public static String of(String userId, String ip) {
        if (userId != null) {
            return user(userId);
        }
        if (ip != null) {
            return ip(ip);
        }
        return "none";
    }
}
