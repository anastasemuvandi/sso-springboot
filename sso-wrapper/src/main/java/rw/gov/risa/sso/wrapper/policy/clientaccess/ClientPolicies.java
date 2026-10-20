package rw.gov.risa.sso.wrapper.policy.clientaccess;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A client's user/group policies reduced to the four id sets that decide. Policies are read
 * <b>by type, not by name</b>: every {@code user}/{@code group} policy counts, its
 * {@code logic} decides allow vs deny. Other types (role, js, ...) are ignored.
 * {@code hasPolicies} records whether any user/group policy exists at all, members or not.
 */
public record ClientPolicies(
        Set<String> allowUserIds,
        Set<String> denyUserIds,
        Set<String> allowGroupIds,
        Set<String> denyGroupIds,
        boolean hasPolicies) {

    public static final ClientPolicies NONE = new ClientPolicies(Set.of(), Set.of(), Set.of(), Set.of(), false);

    public ClientPolicies {
        allowUserIds = Set.copyOf(allowUserIds);
        denyUserIds = Set.copyOf(denyUserIds);
        allowGroupIds = Set.copyOf(allowGroupIds);
        denyGroupIds = Set.copyOf(denyGroupIds);
    }

    public static ClientPolicies parse(Collection<AccessPolicy> policies) {
        Set<String> allowU = new LinkedHashSet<>();
        Set<String> denyU = new LinkedHashSet<>();
        Set<String> allowG = new LinkedHashSet<>();
        Set<String> denyG = new LinkedHashSet<>();
        boolean has = false;
        if (policies != null) {
            for (AccessPolicy p : policies) {
                if (p == null || !(p.isUserPolicy() || p.isGroupPolicy())) {
                    continue;
                }
                has = true;
                boolean negative = p.isNegative();
                if (p.isUserPolicy()) {
                    (negative ? denyU : allowU).addAll(PolicyIds.userIds(p.config().get("users")));
                } else {
                    (negative ? denyG : allowG).addAll(PolicyIds.groupIds(p.config().get("groups")));
                }
            }
        }
        return new ClientPolicies(allowU, denyU, allowG, denyG, has);
    }
}
