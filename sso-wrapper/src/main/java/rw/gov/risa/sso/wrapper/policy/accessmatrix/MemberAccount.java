package rw.gov.risa.sso.wrapper.policy.accessmatrix;

import java.util.List;
import java.util.Map;

/**
 * A Keycloak account, reduced to what the matrix and overview rules read. {@code attributes} is
 * the Keycloak user attribute map (multi-valued); {@code requiredActions} its pending required
 * actions.
 */
public record MemberAccount(
        String id,
        String username,
        String email,
        boolean enabled,
        Map<String, List<String>> attributes,
        List<String> requiredActions) {

    public MemberAccount {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        requiredActions = requiredActions == null ? List.of() : List.copyOf(requiredActions);
    }

    public MemberAccount(String id, String username, String email, boolean enabled, Map<String, List<String>> attributes) {
        this(id, username, email, enabled, attributes, List.of());
    }

    public List<String> attribute(String name) {
        List<String> v = attributes.get(name);
        return v == null ? List.of() : v;
    }
}
