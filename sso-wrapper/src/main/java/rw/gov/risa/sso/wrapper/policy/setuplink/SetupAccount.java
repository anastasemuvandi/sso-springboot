package rw.gov.risa.sso.wrapper.policy.setuplink;

import java.util.List;
import java.util.Map;

/**
 * A Keycloak account, reduced to what the set-password rules read. {@code requiredActions} is the
 * account's pending required actions; {@code attributes} the (multi-valued) user attributes.
 */
public record SetupAccount(
        String id,
        String username,
        String email,
        boolean enabled,
        List<String> requiredActions,
        Map<String, List<String>> attributes) {

    public SetupAccount {
        requiredActions = requiredActions == null ? List.of() : List.copyOf(requiredActions);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public SetupAccount(String id, String username, String email, boolean enabled, List<String> requiredActions) {
        this(id, username, email, enabled, requiredActions, Map.of());
    }
}
