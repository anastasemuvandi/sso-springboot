package rw.gov.risa.sso.wrapper.policy.joinkey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether a user has a usable key for a client, and if not, why.
 *
 * @param ok        true ⇒ the token may be issued
 * @param column    the column evaluated
 * @param value     the resolved raw value (only when {@code ok})
 * @param error     the refusal, or {@code null} when ok
 * @param message   human explanation (never echoes the offending value on refusal paths)
 * @param conflicts other user ids holding the same value (not-unique case) — ids only
 */
public record JoinKeyResolution(boolean ok, JoinColumn column, String value, JoinKeyError error, String message,
                                List<String> conflicts) {

    public JoinKeyResolution {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    /**
     * What the API returns: {@code join_column, claim, ok, message} + {@code value} when ok, else
     * {@code error} (code) and {@code conflicts} (a count, never ids) when there are any.
     */
    public Map<String, Object> describe() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("join_column", column.value());
        body.put("claim", JoinKeys.JOIN_KEY_CLAIM);
        body.put("ok", ok);
        body.put("message", message);
        if (ok) {
            body.put("value", value);
        } else {
            body.put("error", error == null ? null : error.code());
            if (!conflicts.isEmpty()) {
                body.put("conflicts", conflicts.size());
            }
        }
        return body;
    }
}
