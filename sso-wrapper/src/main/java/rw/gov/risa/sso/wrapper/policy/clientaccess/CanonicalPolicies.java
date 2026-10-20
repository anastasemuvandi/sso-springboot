package rw.gov.risa.sso.wrapper.policy.clientaccess;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The three policies the wrapper owns on every client, and the write plan that moves a
 * client's policies to a desired {@link ClientAssignment}.
 *
 * <p>Reads are by type ({@link ClientPolicies}); WRITES find the policy to update by
 * name + type, exactly as the reference does, so a hand-made policy is never overwritten.
 * An empty list deletes its policy (an abandoned empty policy would otherwise keep the client
 * in the "policies define no members" branch forever).
 */
public final class CanonicalPolicies {

    public static final String GROUPS = "sso-access-groups";
    public static final String USERS = "sso-access-users";
    public static final String DENIED_USERS = "sso-access-denied-users";
    public static final String DECISION_STRATEGY = "UNANIMOUS";

    private CanonicalPolicies() {
    }

    /** The body of one canonical policy (group members always {@code extendChildren:false}). */
    public record PolicyBody(String name, String type, String logic, String decisionStrategy,
                             List<String> userIds, List<String> groupIds) {
        public PolicyBody {
            userIds = List.copyOf(userIds);
            groupIds = List.copyOf(groupIds);
        }
    }

    public enum Op { CREATE, UPDATE, DELETE }

    /** One write. {@code existingId} is set for UPDATE/DELETE; {@code body} for CREATE/UPDATE. */
    public record PolicyChange(Op op, String existingId, PolicyBody body) {
    }

    /**
     * The changes that take {@code current} policies to {@code desired}, in the reference's
     * order: groups, users, then the deny-list (lifting a deny AFTER the allow is written, so a
     * failed write never leaves someone with neither). Unchanged policies emit nothing.
     */
    public static List<PolicyChange> plan(Collection<AccessPolicy> current, ClientAssignment desired) {
        ClientAssignment now = ClientAssignment.fromPolicies(current);
        List<PolicyChange> out = new ArrayList<>();
        Optional<AccessPolicy> group = find(current, GROUPS, AccessPolicy.TYPE_GROUP);
        Optional<AccessPolicy> users = find(current, USERS, AccessPolicy.TYPE_USER);
        Optional<AccessPolicy> denied = find(current, DENIED_USERS, AccessPolicy.TYPE_USER);

        boolean groupsChanged = !now.groupIds().equals(desired.groupIds())
                || group.isPresent() != !desired.groupIds().isEmpty();
        if (groupsChanged) {
            change(out, group, desired.groupIds().isEmpty(),
                    new PolicyBody(GROUPS, AccessPolicy.TYPE_GROUP, AccessPolicy.LOGIC_POSITIVE, DECISION_STRATEGY,
                            List.of(), desired.groupIds()));
        }
        boolean usersChanged = !now.userIds().equals(desired.userIds())
                || users.isPresent() != !desired.userIds().isEmpty();
        if (usersChanged) {
            change(out, users, desired.userIds().isEmpty(),
                    new PolicyBody(USERS, AccessPolicy.TYPE_USER, AccessPolicy.LOGIC_POSITIVE, DECISION_STRATEGY,
                            desired.userIds(), List.of()));
        }
        boolean deniedChanged = !now.deniedUserIds().equals(desired.deniedUserIds())
                || denied.isPresent() != !desired.deniedUserIds().isEmpty();
        if (deniedChanged) {
            change(out, denied, desired.deniedUserIds().isEmpty(),
                    new PolicyBody(DENIED_USERS, AccessPolicy.TYPE_USER, AccessPolicy.LOGIC_NEGATIVE,
                            DECISION_STRATEGY, desired.deniedUserIds(), List.of()));
        }
        return out;
    }

    /** Whether the resource server must be enabled first (any CREATE/UPDATE). */
    public static boolean needsAuthorizationServices(List<PolicyChange> changes) {
        return changes.stream().anyMatch(c -> c.op() != Op.DELETE);
    }

    private static void change(List<PolicyChange> out, Optional<AccessPolicy> existing, boolean empty,
                               PolicyBody body) {
        if (empty) {
            existing.ifPresent(p -> out.add(new PolicyChange(Op.DELETE, p.id(), null)));
        } else if (existing.isPresent()) {
            out.add(new PolicyChange(Op.UPDATE, existing.get().id(), body));
        } else {
            out.add(new PolicyChange(Op.CREATE, null, body));
        }
    }

    private static Optional<AccessPolicy> find(Collection<AccessPolicy> policies, String name, String type) {
        if (policies == null) {
            return Optional.empty();
        }
        return policies.stream().filter(p -> p != null && type.equals(p.type()) && name.equals(p.name())).findFirst();
    }
}
