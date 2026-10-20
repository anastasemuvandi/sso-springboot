package rw.gov.risa.sso.wrapper.policy.joinkey;

import java.util.LinkedHashMap;
import java.util.Map;

/** One user a candidate column would block. */
public record ReadinessRow(String userId, String username, JoinKeyError error, String detail) {

    /** {@code user_id, username, error (code), detail}. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", userId);
        out.put("username", username);
        out.put("error", error.code());
        out.put("detail", detail);
        return out;
    }
}
