package rw.gov.risa.sso.wrapper.service.institutionadmin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import rw.gov.risa.sso.wrapper.policy.accessmatrix.MemberAccount;

/**
 * Keycloak representations as the JSON the reference returns (Keycloak's own camelCase keys for
 * Keycloak objects). Insertion-ordered maps; secrets are never copied.
 */
public final class InstitutionViews {

    private InstitutionViews() {
    }

    /** A user as listed ({@code GET /users}). */
    public static Map<String, Object> user(UserRepresentation u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("email", u.getEmail());
        m.put("firstName", u.getFirstName());
        m.put("lastName", u.getLastName());
        m.put("enabled", u.isEnabled() == null || u.isEnabled());
        m.put("emailVerified", Boolean.TRUE.equals(u.isEmailVerified()));
        m.put("createdTimestamp", u.getCreatedTimestamp());
        m.put("attributes", u.getAttributes() == null ? Map.of() : u.getAttributes());
        m.put("requiredActions", u.getRequiredActions() == null ? List.of() : u.getRequiredActions());
        return m;
    }

    /** A user with their groups ({@code agencies}) and direct realm roles ({@code GET /users/{id}}). */
    public static Map<String, Object> userDetail(UserRepresentation u, List<GroupRepresentation> groups,
                                                 List<String> realmRoles) {
        Map<String, Object> m = user(u);
        m.put("agencies", groups.stream().map(g -> {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", g.getId());
            a.put("name", g.getName());
            a.put("path", g.getPath() != null ? g.getPath() : "/" + g.getName());
            return a;
        }).toList());
        m.put("roles", realmRoles.stream().filter(r -> !r.startsWith("default-roles-")).toList());
        return m;
    }

    public static Map<String, Object> role(RoleRepresentation r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("description", r.getDescription());
        m.put("composite", r.isComposite());
        m.put("clientRole", r.getClientRole());
        m.put("containerId", r.getContainerId());
        return m;
    }

    /** A newly created client, without its secret ({@code GET /apps/{id}/secret} issues that). */
    public static Map<String, Object> client(ClientRepresentation c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("clientId", c.getClientId());
        m.put("name", c.getName());
        m.put("enabled", c.isEnabled());
        m.put("publicClient", c.isPublicClient());
        m.put("protocol", c.getProtocol());
        m.put("redirectUris", c.getRedirectUris());
        m.put("standardFlowEnabled", c.isStandardFlowEnabled());
        m.put("directAccessGrantsEnabled", c.isDirectAccessGrantsEnabled());
        m.put("serviceAccountsEnabled", c.isServiceAccountsEnabled());
        m.put("authorizationServicesEnabled", c.getAuthorizationServicesEnabled());
        m.put("attributes", c.getAttributes() == null ? Map.of() : c.getAttributes());
        return m;
    }

    /** The account as the overview / matrix rules read it. */
    public static MemberAccount member(UserRepresentation u) {
        return new MemberAccount(u.getId(), u.getUsername(), u.getEmail(), u.isEnabled() == null || u.isEnabled(),
                u.getAttributes(), u.getRequiredActions());
    }
}
