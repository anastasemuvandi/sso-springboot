package rw.gov.risa.sso.wrapper.policy.clientaccess;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * THE per-client login rule (port of {@code client_access_decision.py}), shared by the token
 * gate, the user page, the overview pages and provisioning. Order matters:
 * <ol>
 *   <li>an exempt client admits everyone;</li>
 *   <li>no user/group policy at all → the realm stance ({@code ACCESS_DEFAULT_DENY});</li>
 *   <li>a deny entry (by name — {@code sso-access-denied-users} — or one of their groups)
 *       beats every allow;</li>
 *   <li>allow rules admit only who they name, or whose DIRECT group they name;</li>
 *   <li>only deny rules → everyone not on them;</li>
 *   <li>policies that name nobody count as no assignment (never grants everyone).</li>
 * </ol>
 * Group membership is DIRECT: pass the person's own group ids, not ancestors.
 */
public final class ClientAccessDecider {

    private ClientAccessDecider() {
    }

    public static AccessDecision decide(String userId, Collection<String> userGroupIds, ClientPolicies policies,
                                        boolean exempt, boolean defaultDeny) {
        if (exempt) {
            return new AccessDecision(true, AccessReason.EXEMPT, false, List.of(), false, false, List.of());
        }
        Set<String> groups = userGroupIds == null ? Set.of()
                : userGroupIds.stream().filter(g -> g != null && !g.isEmpty()).collect(Collectors.toSet());
        boolean direct = userId != null && policies.allowUserIds().contains(userId);
        List<String> via = sortedIntersection(groups, policies.allowGroupIds());
        boolean deniedByName = userId != null && policies.denyUserIds().contains(userId);
        List<String> deniedGroups = sortedIntersection(groups, policies.denyGroupIds());
        boolean denied = deniedByName || !deniedGroups.isEmpty();

        AccessReason reason;
        boolean has;
        if (!policies.hasPolicies()) {
            reason = AccessReason.NO_POLICY;
            has = !defaultDeny;
        } else if (denied) {
            reason = AccessReason.DENIED;
            has = false;
        } else if (!policies.allowUserIds().isEmpty() || !policies.allowGroupIds().isEmpty()) {
            has = direct || !via.isEmpty();
            reason = has ? AccessReason.ASSIGNED : AccessReason.NOT_ASSIGNED;
        } else if (!policies.denyUserIds().isEmpty() || !policies.denyGroupIds().isEmpty()) {
            reason = AccessReason.DENY_LIST_ONLY;
            has = true;
        } else {
            reason = AccessReason.EMPTY_POLICIES;
            has = !defaultDeny;
        }
        return new AccessDecision(has, reason, direct, via, denied, deniedByName, deniedGroups);
    }

    /** Convenience: exemption resolved from the client id and the configured exempt list. */
    public static AccessDecision decide(String userId, Collection<String> userGroupIds, String clientId,
                                        Collection<AccessPolicy> policies, Collection<String> exemptClients,
                                        boolean defaultDeny) {
        boolean exempt = ClientAccessExemptions.isExempt(clientId, exemptClients);
        return decide(userId, userGroupIds, exempt ? ClientPolicies.NONE : ClientPolicies.parse(policies),
                exempt, defaultDeny);
    }

    private static List<String> sortedIntersection(Set<String> a, Set<String> b) {
        return a.stream().filter(b::contains).filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new)).stream().toList();
    }
}
