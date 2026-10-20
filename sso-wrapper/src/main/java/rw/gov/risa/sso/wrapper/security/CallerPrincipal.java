package rw.gov.risa.sso.wrapper.security;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The authenticated caller, built from a VERIFIED access token. The only source of truth for
 * "who is acting" — audit actors and lifecycle actors come from here, never from a request body
 * (spec §7.7).
 *
 * @param userId            {@code sub}
 * @param username          {@code preferred_username} (always present: a token without it gets 401)
 * @param email             {@code email} claim, may be null
 * @param name              {@code name} claim, may be null
 * @param azp               the client the token was issued to
 * @param realmRoles        {@code realm_access.roles}
 * @param clientRoles       {@code resource_access.<client>.roles}, ALL clients incl. realm-management
 *                          (the delegated guardrail is defined over those)
 * @param institutionClaim  the {@code institution} claim lower-cased, if a mapper supplies it
 *                          (corroboration only — authority comes from the {@code inst:<slug>:*} role)
 * @param sessionId         {@code sid}/{@code session_state}, may be null
 * @param accessToken       the raw bearer, for as-caller Keycloak calls. Never log it.
 * @param claims            all claims, read-only
 */
public record CallerPrincipal(
        String userId,
        String username,
        String email,
        String name,
        String azp,
        Set<String> realmRoles,
        Map<String, Set<String>> clientRoles,
        String institutionClaim,
        String sessionId,
        String accessToken,
        Map<String, Object> claims) {

    /** Platform admin tier: realm role super_admin | admin | system_admin. */
    public static final List<String> ADMIN_TIER_ROLES = List.of("super_admin", "admin", "system_admin");

    public boolean hasRealmRole(String role) {
        return realmRoles.contains(role);
    }

    public boolean isPlatformAdmin() {
        return ADMIN_TIER_ROLES.stream().anyMatch(realmRoles::contains);
    }

    public boolean isSuperAdmin() {
        return realmRoles.contains("super_admin");
    }

    public Set<String> clientRoles(String clientId) {
        return clientRoles.getOrDefault(clientId, Set.of());
    }

    public Optional<String> institution() {
        return Optional.ofNullable(institutionClaim);
    }

    @Override
    public String toString() {
        // Never print the token.
        return "CallerPrincipal[username=" + username + ", userId=" + userId + ", azp=" + azp + "]";
    }
}
