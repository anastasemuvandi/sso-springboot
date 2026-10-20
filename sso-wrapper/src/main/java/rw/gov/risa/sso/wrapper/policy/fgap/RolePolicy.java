package rw.gov.risa.sso.wrapper.policy.fgap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** "Who": anyone holding one of the listed grant roles (Keycloak role policy, {@code required=false}). */
public record RolePolicy(String name, String description, List<String> roles) {

    public RolePolicy {
        roles = List.copyOf(roles);
    }

    /** {@code {"name", "description", "roles"}} in that order. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("roles", roles);
        return m;
    }
}
