package rw.gov.risa.sso.wrapper.policy.delegated;

/**
 * One broad-admin grant an institution admin must not hold.
 *
 * @param kind   realm role or client role
 * @param role   the role name
 * @param client the clientId for a client role, {@code null} for a realm role
 */
public record GuardrailViolation(Kind kind, String role, String client) {

    public enum Kind {
        REALM_ROLE("realm-role"),
        CLIENT_ROLE("client-role");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        @Override
        public String toString() {
            return wire;
        }
    }

    public static GuardrailViolation realmRole(String role) {
        return new GuardrailViolation(Kind.REALM_ROLE, role, null);
    }

    public static GuardrailViolation clientRole(String client, String role) {
        return new GuardrailViolation(Kind.CLIENT_ROLE, role, client);
    }

    /** {@code "realm role admin"} or {@code "realm-management:manage-users"}. */
    @Override
    public String toString() {
        String where = (client != null && !client.isEmpty()) ? client + ":" : "realm role ";
        return where + role;
    }
}
