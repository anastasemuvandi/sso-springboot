package rw.gov.risa.sso.audit.chain;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code infer_category} from the reference: the category is derived from the event_type prefix. */
public final class EventCategories {

    public static final String UNKNOWN = "UNKNOWN";

    private static final Map<String, String> PREFIX = new LinkedHashMap<>();

    static {
        PREFIX.put("auth.", "AUTHENTICATION");
        PREFIX.put("authz.", "AUTHORIZATION");
        PREFIX.put("admin.", "ADMIN");
        PREFIX.put("lifecycle.", "LIFECYCLE");
        PREFIX.put("session.", "SESSION");
        PREFIX.put("cert.", "CERTIFICATE");
    }

    private EventCategories() {
    }

    /** First matching prefix wins ({@code auth.} is checked before {@code authz.}, which it cannot shadow). */
    public static String infer(String eventType) {
        if (eventType != null) {
            for (Map.Entry<String, String> e : PREFIX.entrySet()) {
                if (eventType.startsWith(e.getKey())) {
                    return e.getValue();
                }
            }
        }
        return UNKNOWN;
    }
}
