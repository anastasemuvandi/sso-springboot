package rw.gov.risa.sso.wrapper.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One auditable fact.
 *
 * @param type       what happened
 * @param outcome    "success" | "failure" | "denied" (plus feature-specific outcomes such as "skipped")
 * @param actor      username of the authenticated principal — never taken from a request body
 * @param clientId   OAuth2 client involved, if any
 * @param session    Keycloak session id, if known
 * @param requestIp  remote IP, if an HTTP request is involved
 * @param extra      event-specific detail. Keys {@code institution} and {@code target_user} /
 *                   {@code target} / {@code target_client} are lifted into first-class columns.
 */
public record AuditEvent(
        AuditEventType type,
        String outcome,
        String actor,
        String clientId,
        String session,
        String requestIp,
        Map<String, Object> extra) {

    public AuditEvent {
        extra = extra == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }

    public static Builder of(AuditEventType type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final AuditEventType type;
        private String outcome = "success";
        private String actor;
        private String clientId;
        private String session;
        private String requestIp;
        private final Map<String, Object> extra = new LinkedHashMap<>();

        private Builder(AuditEventType type) {
            this.type = type;
        }

        public Builder outcome(String v) { this.outcome = v; return this; }
        public Builder success() { return outcome("success"); }
        public Builder failure() { return outcome("failure"); }
        public Builder denied() { return outcome("denied"); }
        public Builder actor(String v) { this.actor = v; return this; }
        public Builder clientId(String v) { this.clientId = v; return this; }
        public Builder session(String v) { this.session = v; return this; }
        public Builder requestIp(String v) { this.requestIp = v; return this; }
        public Builder institution(String v) { return put("institution", v); }
        public Builder targetUser(String v) { return put("target_user", v); }
        public Builder targetClient(String v) { return put("target_client", v); }

        public Builder put(String key, Object value) {
            if (value != null) {
                extra.put(key, value);
            }
            return this;
        }

        public Builder putAll(Map<String, ?> values) {
            if (values != null) {
                values.forEach(this::put);
            }
            return this;
        }

        public AuditEvent build() {
            return new AuditEvent(type, outcome, actor, clientId, session, requestIp, extra);
        }
    }
}
