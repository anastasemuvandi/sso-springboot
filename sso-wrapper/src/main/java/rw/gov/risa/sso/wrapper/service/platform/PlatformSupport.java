package rw.gov.risa.sso.wrapper.service.platform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.KeycloakErrors;
import rw.gov.risa.sso.wrapper.policy.audit.AuditScope;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScopeKind;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * What every platform-admin service shares: the service-account executor (platform routes are
 * realm-wide by definition — the admin-tier gate already ran), client lookup by clientId OR uuid,
 * and the audit target fields the reference's {@code audit_scope} attaches (target ids, the
 * institution an action belongs to). The actor ALWAYS comes from the authenticated caller.
 */
@Component
public class PlatformSupport {

    /** Keycloak's own clients, hidden from the user/role views as the reference does. */
    public static final Set<String> KEYCLOAK_INTERNAL_CLIENTS = Set.of("account", "account-console", "admin-cli",
            "broker", "realm-management", "security-admin-console");

    private final KeycloakExecutors executors;
    private final KeycloakDirectory directory;
    private final ClientScopeService scopes;
    private final AuditEmitter audit;

    public PlatformSupport(KeycloakExecutors executors, KeycloakDirectory directory, ClientScopeService scopes,
                           AuditEmitter audit) {
        this.executors = executors;
        this.directory = directory;
        this.scopes = scopes;
        this.audit = audit;
    }

    public KeycloakExec sa() {
        return executors.serviceAccount();
    }

    public KeycloakDirectory directory() {
        return directory;
    }

    public ClientScopeService scopes() {
        return scopes;
    }

    // ---- lookups ---------------------------------------------------------------------------

    /**
     * The client named by {@code id}: its {@code clientId} first (what the reference's client routes
     * take), else its internal uuid (what the role and authorization routes take). 404 otherwise.
     */
    public ClientRepresentation requireClient(RealmResource realm, String id) {
        return findClient(realm, id)
                .orElseThrow(() -> ApiException.notFound("Client '" + id + "' not found."));
    }

    public Optional<ClientRepresentation> findClient(RealmResource realm, String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Optional<ClientRepresentation> byClientId = directory.findClient(realm, id);
        if (byClientId.isPresent()) {
            return byClientId;
        }
        try {
            return Optional.ofNullable(realm.clients().get(id).toRepresentation());
        } catch (NotFoundException e) {
            return Optional.empty();
        } catch (WebApplicationException e) {
            if (KeycloakErrors.status(e) == 404 || KeycloakErrors.status(e) == 400) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public UserRepresentation requireUser(RealmResource realm, String userId) {
        return directory.getUser(realm, userId).orElseThrow(() -> ApiException.notFound("User not found"));
    }

    public GroupRepresentation requireGroup(RealmResource realm, String groupId) {
        try {
            return directory.getGroup(realm, groupId)
                    .orElseThrow(() -> ApiException.notFound("Group '" + groupId + "' not found"));
        } catch (WebApplicationException e) {
            if (KeycloakErrors.status(e) == 404) {
                throw ApiException.notFound("Group '" + groupId + "' not found");
            }
            throw e;
        }
    }

    /** The id of a resource Keycloak just created (Location header), or null. */
    public static String createdId(Response response) {
        if (response.getLocation() == null) {
            return null;
        }
        String path = response.getLocation().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** The {@code errorMessage} of a refused Keycloak write, or null. */
    public static String errorMessage(Response response) {
        try {
            if (!response.hasEntity()) {
                return null;
            }
            String body = response.readEntity(String.class);
            if (body == null || body.isBlank()) {
                return null;
            }
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            if (node.hasNonNull("errorMessage")) {
                return node.get("errorMessage").asText();
            }
            if (node.hasNonNull("error_description")) {
                return node.get("error_description").asText();
            }
            if (node.hasNonNull("error")) {
                return node.get("error").asText();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- audit -----------------------------------------------------------------------------

    /** A builder pre-filled with the caller as actor and the request IP. */
    public AuditEvent.Builder event(AuditEventType type, CallerPrincipal caller) {
        return AuditEvent.of(type).actor(caller.username()).requestIp(RequestInfo.clientIp());
    }

    public void emit(AuditEvent.Builder event) {
        audit.emit(event);
    }

    /** {@code client_target}: the client and, for an institution client, its owner. */
    public Map<String, Object> clientTarget(ClientRepresentation client) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (client == null) {
            return out;
        }
        out.put("target_client", client.getClientId());
        ClientScope scope = scopes.scopeOf(client);
        if (scope.kind() == ClientScopeKind.INSTITUTION && scope.owner() != null) {
            out.put("institution", scope.owner());
        }
        return out;
    }

    /** {@link #clientTarget(ClientRepresentation)} by id, never failing (an audit tag is never worth an error). */
    public Map<String, Object> clientTarget(String clientId) {
        try {
            return sa().call(realm -> findClient(realm, clientId).map(this::clientTarget)
                    .orElseGet(() -> new LinkedHashMap<>(Map.of("target_client", clientId))));
        } catch (RuntimeException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (clientId != null) {
                out.put("target_client", clientId);
            }
            return out;
        }
    }

    /** {@code user_target}: the user, their username and their institution attribute. */
    public static Map<String, Object> userTarget(UserRepresentation user, String institutionOverride) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (user == null) {
            return out;
        }
        out.put("target_user", user.getId());
        if (user.getUsername() != null) {
            out.put("target_username", user.getUsername());
        }
        String inst = institutionOverride != null ? institutionOverride
                : AuditScope.institutionOfUser(user.getAttributes());
        out.putAll(AuditScope.institutionField(inst));
        return out;
    }

    /** {@link #userTarget(UserRepresentation, String)} by id, never failing. */
    public Map<String, Object> userTarget(String userId, String institutionOverride) {
        try {
            Optional<UserRepresentation> user = sa().call(realm -> directory.getUser(realm, userId));
            if (user.isPresent()) {
                return userTarget(user.get(), institutionOverride);
            }
        } catch (RuntimeException e) {
            // fall through
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target_user", userId);
        out.putAll(AuditScope.institutionField(institutionOverride));
        return out;
    }

    /** {@code group_target}: the group id, name, path and the institution it is (under). */
    public Map<String, Object> groupTarget(GroupRepresentation group) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (group == null) {
            return out;
        }
        out.put("target", group.getId());
        out.put("group_name", group.getName());
        out.put("group_path", group.getPath());
        out.putAll(AuditScope.institutionField(institutionOfGroup(group)));
        return out;
    }

    public Map<String, Object> groupTarget(String groupId) {
        try {
            Optional<GroupRepresentation> g = sa().call(realm -> directory.getGroup(realm, groupId));
            if (g.isPresent()) {
                return groupTarget(g.get());
            }
        } catch (RuntimeException e) {
            // fall through
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", groupId);
        return out;
    }

    public String institutionOfGroup(GroupRepresentation group) {
        return AuditScope.institutionOfGroup(group.getPath(), group.getName(), scopes.knownSlugs());
    }

    /**
     * {@code membership_target}: the user, tagged with the institution of the groups when they are all
     * in one institution (else the user's own).
     */
    public Map<String, Object> membershipTarget(String userId, Collection<String> groupIds) {
        Set<String> institutions = new LinkedHashSet<>();
        try {
            sa().run(realm -> {
                for (String gid : groupIds) {
                    directory.getGroup(realm, gid).map(this::institutionOfGroup).ifPresent(institutions::add);
                }
            });
        } catch (RuntimeException e) {
            // untagged
        }
        String inst = institutions.size() == 1 ? institutions.iterator().next() : null;
        return userTarget(userId, inst);
    }

    /** Message of a Keycloak failure for a 400 body. */
    public static String reason(RuntimeException e) {
        if (e instanceof WebApplicationException w) {
            return KeycloakErrors.message(w, "Keycloak returned " + KeycloakErrors.status(w));
        }
        if (e instanceof ProcessingException) {
            return "Identity provider unreachable";
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    public static <T> List<T> nonNull(List<T> in) {
        return in == null ? new ArrayList<>() : in;
    }
}
