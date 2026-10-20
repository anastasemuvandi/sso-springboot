package rw.gov.risa.sso.wrapper.service.lifecycle;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * The Keycloak user operations the IPPIS sync and the lifecycle need, over ONE service-account
 * admin client for a unit of work (a request, or one reconcile run). Obtained from
 * {@link KeycloakUserGateway#open()} and closed afterwards. Keycloak is only ever written through
 * the Admin REST API.
 */
public interface UserOps extends AutoCloseable {

    Optional<UserRepresentation> getUser(String userId);

    /** One page of users with attributes (full representation). */
    List<UserRepresentation> listUsers(int first, int max);

    /** Users whose attribute {@code name} equals {@code value} EXACTLY. */
    List<UserRepresentation> findByAttribute(String name, String value);

    /**
     * Write single-valued attributes ({@code null} value = remove). Re-reads the user, never
     * changes the {@code email} property, returns false when the user does not exist.
     */
    boolean updateAttributes(String userId, Map<String, String> write);

    /** Enable/disable and apply attribute changes ({@code null} list = remove) in one update. */
    void setEnabled(String userId, boolean enabled, Map<String, List<String>> attributeChanges);

    List<GroupRepresentation> groups(String userId);

    int sessionCount(String userId);

    /** End every session of the user. */
    void logout(String userId);

    void removeFromGroup(String userId, String groupId);

    void addToGroup(String userId, String groupId);

    /** DIRECT realm-role mappings. */
    List<RoleRepresentation> realmRoles(String userId);

    void removeRealmRoles(String userId, List<RoleRepresentation> roles);

    void addRealmRoles(String userId, List<RoleRepresentation> roles);

    /** Exact-name group anywhere in the tree (first match, top-level first). */
    Optional<GroupRepresentation> findGroupByName(String name);

    @Override
    void close();
}
