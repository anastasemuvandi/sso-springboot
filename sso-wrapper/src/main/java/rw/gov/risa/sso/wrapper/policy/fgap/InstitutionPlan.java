package rw.gov.risa.sso.wrapper.policy.fgap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every tier enabled for one institution, projected into non-overlapping objects. The
 * realm-wide role-mapping gate uses institution {@link FgapProjection#SHARED} ({@code "_shared"}).
 */
public record InstitutionPlan(String institution, List<RolePolicy> policies, List<ScopePermission> permissions) {

    public InstitutionPlan {
        policies = List.copyOf(policies);
        permissions = List.copyOf(permissions);
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("institution", institution);
        m.put("policies", policies.stream().map(RolePolicy::describe).toList());
        m.put("permissions", permissions.stream().map(ScopePermission::describe).toList());
        m.put("object_count", policies.size() + permissions.size());
        return m;
    }
}
