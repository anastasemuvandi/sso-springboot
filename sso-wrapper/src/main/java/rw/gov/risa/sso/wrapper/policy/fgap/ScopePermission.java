package rw.gov.risa.sso.wrapper.policy.fgap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** "What, on which things": resources + scopes + the policy that unlocks them. */
public record ScopePermission(String name, String description, ResourceType resourceType, List<String> resources,
                              List<String> scopes, List<String> policies) {

    public ScopePermission {
        resources = List.copyOf(resources);
        scopes = List.copyOf(scopes);
        policies = List.copyOf(policies);
    }

    /** {@code {"name", "description", "resource_type", "resources", "scopes", "policies"}} in that order. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("resource_type", resourceType.wire());
        m.put("resources", resources);
        m.put("scopes", scopes);
        m.put("policies", policies);
        return m;
    }
}
