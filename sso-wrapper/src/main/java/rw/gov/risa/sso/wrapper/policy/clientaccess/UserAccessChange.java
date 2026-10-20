package rw.gov.risa.sso.wrapper.policy.clientaccess;

import java.util.Optional;

/**
 * Grant or revoke one client for one person from the platform user page
 * ({@code POST /authorization/users/{userId}/client-access/{clientId}}), the pure checks.
 * Refusals are 409 with {@code reason} = {@code state_changed}, {@code not_applicable} or
 * {@code denied_by_group}; nothing is written in any refusal.
 */
public final class UserAccessChange {

    public static final String STATE_CHANGED = "state_changed";
    public static final String NOT_APPLICABLE = "not_applicable";
    public static final String DENIED_BY_GROUP = "denied_by_group";

    /** Audit {@code extra.action} values. */
    public static final String AUDIT_GRANT = "grant_user_access";
    public static final String AUDIT_REVOKE = "revoke_user_access";

    public enum Action {
        GRANT("grant"), REVOKE("revoke");

        private final String wire;

        Action(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Optional<Action> fromWire(String value) {
            for (Action a : values()) {
                if (a.wire.equals(value)) {
                    return Optional.of(a);
                }
            }
            return Optional.empty();
        }
    }

    /** How a revoke is carried out. */
    public enum RevokeMode {
        /** Held by name and/or via a group: {@link ClientAccessWrites#revokeUsers}. */
        UNASSIGN_AND_OR_DENY,
        /** {@code open}: nothing names them, only a deny entry takes it away. */
        DENY_ENTRY
    }

    public record Refusal(String reason, AccessState currentState) {
    }

    private UserAccessChange() {
    }

    /** The 409 checks, in the reference's order. Empty → the change may proceed. */
    public static Optional<Refusal> check(Action action, String expectedState, AccessDecision current) {
        AccessState previous = current.state();
        if (!previous.wire().equals(expectedState)) {
            return Optional.of(new Refusal(STATE_CHANGED, previous));
        }
        boolean allowed = action == Action.GRANT
                ? AccessState.GRANTABLE.contains(previous)
                : AccessState.REVOCABLE.contains(previous);
        if (!allowed) {
            return Optional.of(new Refusal(NOT_APPLICABLE, previous));
        }
        if (action == Action.GRANT && !current.deniedGroupIds().isEmpty() && !current.deniedByName()) {
            return Optional.of(new Refusal(DENIED_BY_GROUP, previous));
        }
        return Optional.empty();
    }

    public static RevokeMode revokeMode(AccessDecision current) {
        return current.direct() || !current.viaGroupIds().isEmpty() ? RevokeMode.UNASSIGN_AND_OR_DENY
                : RevokeMode.DENY_ENTRY;
    }

    public static String auditAction(Action action) {
        return action == Action.GRANT ? AUDIT_GRANT : AUDIT_REVOKE;
    }
}
