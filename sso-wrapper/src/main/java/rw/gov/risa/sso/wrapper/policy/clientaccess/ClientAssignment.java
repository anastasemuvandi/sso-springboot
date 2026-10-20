package rw.gov.risa.sso.wrapper.policy.clientaccess;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A client's access assignment in the shape the wrapper writes it: the named users
 * ({@code sso-access-users}), the admitted groups ({@code sso-access-groups}) and the deny-list
 * ({@code sso-access-denied-users}). Order-preserving and de-duplicated.
 */
public record ClientAssignment(List<String> userIds, List<String> groupIds, List<String> deniedUserIds) {

    public static final ClientAssignment EMPTY = new ClientAssignment(List.of(), List.of(), List.of());

    public ClientAssignment {
        userIds = dedupe(userIds);
        groupIds = dedupe(groupIds);
        deniedUserIds = dedupe(deniedUserIds);
    }

    /**
     * Read the current assignment from the client's policies: allows from every POSITIVE
     * user/group policy (by type, like {@code get_client_assignments}); the deny-list from the
     * canonical {@code sso-access-denied-users} NEGATIVE user policy (the one the wrapper
     * writes). Malformed config contributes nothing.
     */
    public static ClientAssignment fromPolicies(Collection<AccessPolicy> policies) {
        List<String> users = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        if (policies != null) {
            for (AccessPolicy p : policies) {
                if (p == null) {
                    continue;
                }
                if (p.isNegative()) {
                    if (p.isUserPolicy() && CanonicalPolicies.DENIED_USERS.equals(p.name())) {
                        denied.addAll(PolicyIds.userIds(p.config().get("users")));
                    }
                    continue;
                }
                if (p.isGroupPolicy()) {
                    groups.addAll(PolicyIds.groupIds(p.config().get("groups")));
                } else if (p.isUserPolicy()) {
                    users.addAll(PolicyIds.userIds(p.config().get("users")));
                }
            }
        }
        return new ClientAssignment(users, groups, denied);
    }

    /** True when a user or group is assigned ({@code enforced}). */
    public boolean enforced() {
        return !userIds.isEmpty() || !groupIds.isEmpty();
    }

    public ClientAssignment withUsers(List<String> users) {
        return new ClientAssignment(users, groupIds, deniedUserIds);
    }

    public ClientAssignment withGroups(List<String> groups) {
        return new ClientAssignment(userIds, groups, deniedUserIds);
    }

    public ClientAssignment withDenied(List<String> denied) {
        return new ClientAssignment(userIds, groupIds, denied);
    }

    static List<String> dedupe(Collection<String> ids) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isEmpty()) {
                    out.add(id);
                }
            }
        }
        return List.copyOf(out);
    }
}
