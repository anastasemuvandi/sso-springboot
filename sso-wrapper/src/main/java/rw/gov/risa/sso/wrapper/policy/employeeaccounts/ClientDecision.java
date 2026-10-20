package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

import java.util.LinkedHashMap;
import java.util.Map;

import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumn;

/**
 * Whether one employee may be given one client. Three states: qualified and not held (assign
 * now), held (already reaches it — nothing to do), not qualified (refused; {@code reason} and
 * {@code message} name the empty field).
 */
public record ClientDecision(String clientId, JoinColumn column, boolean qualified, boolean held,
                             DenyReason reason, String message) {

    public ClientDecision {
        message = message == null ? "" : message;
    }

    /** Whether this run writes a grant for the pair. */
    public boolean assignable() {
        return qualified && !held;
    }

    /** {@code client_id, join_column, qualified} (+ {@code reason, message} when refused). */
    public Map<String, Object> describe() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", clientId);
        body.put("join_column", column.value());
        body.put("qualified", qualified);
        if (!qualified) {
            body.put("reason", reason == null ? null : reason.code());
            body.put("message", message);
        }
        return body;
    }
}
