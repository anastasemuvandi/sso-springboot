package rw.gov.risa.sso.wrapper.policy.institutioncontext;

import java.util.List;
import java.util.Map;

/**
 * The verified caller, as plain data: what the service layer extracts from the bearer token.
 *
 * @param username         {@code preferred_username}, the audit actor
 * @param userId           {@code sub}
 * @param realmRoles       {@code realm_access.roles}
 * @param clientRoles      {@code resource_access.<clientId>.roles} (may be empty)
 * @param institutionClaim the token's {@code institution} claim, or {@code null} when absent
 */
public record CallerIdentity(String username, String userId, List<String> realmRoles,
                             Map<String, List<String>> clientRoles, String institutionClaim) {

    public CallerIdentity {
        realmRoles = realmRoles == null ? List.of() : List.copyOf(realmRoles);
        clientRoles = clientRoles == null ? Map.of() : Map.copyOf(clientRoles);
    }

    public static CallerIdentity of(String username, String userId, List<String> realmRoles) {
        return new CallerIdentity(username, userId, realmRoles, Map.of(), null);
    }

    public CallerIdentity withClientRoles(Map<String, List<String>> roles) {
        return new CallerIdentity(username, userId, realmRoles, roles, institutionClaim);
    }

    public CallerIdentity withInstitutionClaim(String claim) {
        return new CallerIdentity(username, userId, realmRoles, clientRoles, claim);
    }
}
