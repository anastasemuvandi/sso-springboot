package rw.gov.risa.sso.wrapper.policy.lifecycle;

import java.util.List;
import java.util.Map;

/**
 * What the service read about one account before offboarding it.
 *
 * @param realmRoleNames      the user's DIRECT realm role mappings
 * @param namedOnClients      clientIds whose {@code sso-access-users} policy names this user
 * @param attributes          Keycloak attributes ({@code institution} read for the audit tag)
 */
public record OffboardingInput(
        String userId,
        String username,
        boolean enabled,
        int sessionCount,
        List<GroupRef> groups,
        List<String> realmRoleNames,
        List<String> namedOnClients,
        Map<String, List<String>> attributes) {

    public OffboardingInput {
        groups = groups == null ? List.of() : List.copyOf(groups);
        realmRoleNames = realmRoleNames == null ? List.of() : List.copyOf(realmRoleNames);
        namedOnClients = namedOnClients == null ? List.of() : List.copyOf(namedOnClients);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
