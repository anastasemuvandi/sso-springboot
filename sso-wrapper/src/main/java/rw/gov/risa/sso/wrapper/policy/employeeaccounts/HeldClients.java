package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Which selected clients each resolved employee can <b>already</b> reach.
 *
 * <p>Held = a named assignment on the client, or direct membership of a group the client admits.
 * <b>A denied user is never a holder</b> (the gate checks the deny-list first; granting by name
 * lifts the denial — exactly what the admin asks for). A client whose policies could not be read
 * is treated as held by nobody (grants are idempotent; a wrong "already held" would skip a write).
 */
public final class HeldClients {

    private HeldClients() {
    }

    /**
     * One client's current access, as read from its policies.
     *
     * @param userIds   named users ({@code sso-access-users})
     * @param groupIds  admitted groups ({@code sso-access-groups}, direct members only)
     * @param deniedIds denied users ({@code sso-access-denied-users})
     */
    public record ClientHolders(Set<String> userIds, Set<String> groupIds, Set<String> deniedIds) {

        public ClientHolders {
            userIds = userIds == null ? Set.of() : Set.copyOf(userIds);
            groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
            deniedIds = deniedIds == null ? Set.of() : Set.copyOf(deniedIds);
        }
    }

    /**
     * @param targets        the selected clients
     * @param userIdsByNumber employee number → resolved account id (employees with no account omitted)
     * @param holdersByClient client id → its holders; a missing entry means the read failed
     * @param groupIdsOfUser  user id → the groups they directly belong to (called at most once per
     *                        user, and only when some client admits a group; failure ⇒ empty set)
     * @return employee number → held client ids (every employee with an account appears)
     */
    public static Map<String, Set<String>> compute(List<ClientTarget> targets, Map<String, String> userIdsByNumber,
                                                   Map<String, ClientHolders> holdersByClient,
                                                   Function<String, Set<String>> groupIdsOfUser) {
        Map<String, Set<String>> held = new LinkedHashMap<>();
        userIdsByNumber.forEach((n, id) -> {
            if (id != null && !id.isEmpty()) {
                held.put(n, new LinkedHashSet<>());
            }
        });
        if (held.isEmpty()) {
            return held;
        }
        Map<String, Set<String>> groupsOf = new HashMap<>();
        for (ClientTarget target : targets) {
            ClientHolders holders = holdersByClient.get(target.clientId());
            if (holders == null) {
                continue;
            }
            for (String number : held.keySet()) {
                String userId = userIdsByNumber.get(number);
                if (holders.deniedIds().contains(userId)) {
                    continue;
                }
                if (holders.userIds().contains(userId)) {
                    held.get(number).add(target.clientId());
                    continue;
                }
                if (holders.groupIds().isEmpty()) {
                    continue;
                }
                Set<String> groups = groupsOf.computeIfAbsent(userId, u -> {
                    Set<String> g = groupIdsOfUser.apply(u);
                    return g == null ? Set.of() : g;
                });
                for (String g : groups) {
                    if (holders.groupIds().contains(g)) {
                        held.get(number).add(target.clientId());
                        break;
                    }
                }
            }
        }
        return held;
    }
}
