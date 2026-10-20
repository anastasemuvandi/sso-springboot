package rw.gov.risa.sso.wrapper.service.platformops.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.core.Response;

import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.EventRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScopeKind;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedNamespaces;
import rw.gov.risa.sso.wrapper.policy.registry.RegistryEntry;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.clientaccess.AccessPolicyStore;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionRegistryHolder;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;

/**
 * {@code /admin/*} views (reference {@code api/admin.py}, which returned mock data): the same shapes,
 * filled from Keycloak. Platform-admin tier (RouteRules); {@code /admin/system/users} is super-admin only
 * as in the reference. Writes are audited.
 */
@Service
public class AdminViewsService {

    static final int MAX_MEMBERS = 10_000;

    private final KeycloakExecutors executors;
    private final KeycloakDirectory directory;
    private final AccessPolicyStore policies;
    private final InstitutionRegistryHolder registry;
    private final AuditEmitter audit;

    public AdminViewsService(KeycloakExecutors executors, KeycloakDirectory directory, AccessPolicyStore policies,
                             InstitutionRegistryHolder registry, AuditEmitter audit) {
        this.executors = executors;
        this.directory = directory;
        this.policies = policies;
        this.registry = registry;
        this.audit = audit;
    }

    @Schema(name = "AgencyDashboardResponse")
    public record AgencyDashboard(@JsonProperty("agency_code") String agencyCode, String name,
                                  @JsonProperty("user_count") int userCount, List<Map<String, Object>> groups,
                                  List<String> services) {
    }

    @Schema(name = "ClientDashboardResponse")
    public record ClientDashboard(@JsonProperty("client_id") String clientId, String name, String description,
                                  @JsonProperty("user_count") int userCount,
                                  @JsonProperty("active_sessions") long activeSessions,
                                  @JsonProperty("last_login") String lastLogin, List<String> permissions) {
    }

    @Schema(name = "ClientUserResponse")
    public record ClientUser(@JsonProperty("user_id") String userId, String username, String email,
                             List<String> roles, @JsonProperty("last_login") String lastLogin, String status) {
    }

    @Schema(name = "AssignRolesRequest")
    public record AssignRoles(List<String> roles) {
    }

    // ---- agencies --------------------------------------------------------------------------

    /** {@code GET /admin/agencies/{code}/dashboard}: the institution group, its departments and apps. */
    public AgencyDashboard agencyDashboard(String code) {
        String slug = slug(code);
        return executors.serviceAccount().call(realm -> {
            GroupRepresentation root = institutionGroup(realm, slug);
            Set<String> members = new LinkedHashSet<>();
            for (String gid : directory.groupTreeIds(realm, root.getId())) {
                directory.groupMembers(realm, gid, MAX_MEMBERS).forEach(u -> members.add(u.getId()));
            }
            List<Map<String, Object>> groups = new ArrayList<>();
            for (GroupRepresentation child : directory.groupChildren(realm, root.getId())) {
                groups.add(groupRow(realm, child));
            }
            List<String> services = new ArrayList<>();
            Set<String> known = registry.knownSlugs();
            for (ClientRepresentation c : realm.clients().findAll()) {
                ClientScope scope = ClientScope.parse(c.getClientId(), c.getAttributes(), known);
                boolean owned = scope.kind() == ClientScopeKind.INSTITUTION && slug.equals(scope.owner());
                boolean shared = scope.kind() == ClientScopeKind.CROSS_CUTTING && scope.covers(slug);
                if (owned || shared) {
                    services.add(c.getClientId());
                }
            }
            services.sort(String::compareTo);
            String name = registry.current().entryFor(slug).map(RegistryEntry::displayName)
                    .filter(n -> n != null && !n.isBlank()).orElse(root.getName());
            return new AgencyDashboard(slug, name, members.size(), groups, services);
        });
    }

    /** {@code GET /admin/agencies/{code}/groups}: the institution's departments (direct children). */
    public Map<String, Object> agencyGroups(String code) {
        String slug = slug(code);
        return executors.serviceAccount().call(realm -> {
            GroupRepresentation root = institutionGroup(realm, slug);
            List<Map<String, Object>> groups = new ArrayList<>();
            for (GroupRepresentation child : directory.groupChildren(realm, root.getId())) {
                groups.add(groupRow(realm, child));
            }
            return Map.of("groups", groups);
        });
    }

    /** {@code POST /admin/agencies/{code}/groups}: create a department under the institution group. */
    public Map<String, Object> createAgencyGroup(String code, Map<String, Object> data, CallerPrincipal caller,
                                                 String ip) {
        String slug = slug(code);
        Object rawName = data == null ? null : data.get("name");
        String name = rawName == null ? "" : String.valueOf(rawName).strip();
        if (name.isEmpty() || name.contains("/")) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "A group needs a `name` without '/'.");
        }
        Object description = data.get("description");
        return executors.serviceAccount().call(realm -> {
            GroupRepresentation root = institutionGroup(realm, slug);
            GroupRepresentation g = new GroupRepresentation();
            g.setName(name);
            if (description != null && !String.valueOf(description).isBlank()) {
                g.setAttributes(Map.of("description", List.of(String.valueOf(description).strip())));
            }
            String id;
            try (Response r = realm.groups().group(root.getId()).subGroup(g)) {
                if (r.getStatus() == 409) {
                    throw ApiException.conflict(ErrorCodes.CONFLICT, "A group named '" + name + "' already exists in "
                            + root.getPath() + ".", Map.of());
                }
                if (r.getStatus() >= 300) {
                    throw ApiException.badGateway(ErrorCodes.KEYCLOAK_ERROR,
                            "Keycloak refused the group: HTTP " + r.getStatus());
                }
                id = CreatedResponseUtil.getCreatedId(r);
            }
            audit.emit(AuditEvent.of(AuditEventType.ADMIN_GROUP_CREATED).actor(caller.username()).requestIp(ip)
                    .institution(slug).put("target", id).put("group_name", name)
                    .put("group_path", root.getPath() + "/" + name).build());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Group created successfully");
            out.put("group_id", id);
            out.put("path", root.getPath() + "/" + name);
            return out;
        });
    }

    private Map<String, Object> groupRow(RealmResource realm, GroupRepresentation g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("name", g.getName());
        m.put("path", g.getPath());
        List<String> desc = g.getAttributes() == null ? null : g.getAttributes().get("description");
        m.put("description", desc == null || desc.isEmpty() ? null : desc.get(0));
        m.put("member_count", directory.groupMembers(realm, g.getId(), MAX_MEMBERS).size());
        m.put("sub_group_count", g.getSubGroupCount());
        return m;
    }

    /** The top-level group of an institution, matched case-insensitively (legacy UPPERCASE groups), or 404. */
    GroupRepresentation institutionGroup(RealmResource realm, String slug) {
        Optional<GroupRepresentation> exact = directory.groupByPath(realm, "/" + slug);
        if (exact.isPresent()) {
            return exact.get();
        }
        for (GroupRepresentation g : realm.groups().groups(slug, 0, 100, true)) {
            if (g.getName() != null && g.getName().equalsIgnoreCase(slug)
                    && (g.getPath() == null || g.getPath().equalsIgnoreCase("/" + slug))) {
                return g;
            }
        }
        throw ApiException.notFound("No institution group for '" + slug + "'.");
    }

    // ---- clients ---------------------------------------------------------------------------

    /** {@code GET /admin/clients/{id}/dashboard}. */
    public ClientDashboard clientDashboard(String clientId) {
        return executors.serviceAccount().call(realm -> {
            ClientRepresentation c = directory.requireClient(realm, clientId);
            ClientAssignment a = ClientAssignment.fromPolicies(policies.read(realm, c.getId()));
            Set<String> people = new LinkedHashSet<>(a.userIds());
            for (String gid : a.groupIds()) {
                directory.groupMembers(realm, gid, MAX_MEMBERS).forEach(u -> people.add(u.getId()));
            }
            a.deniedUserIds().forEach(people::remove);
            long sessions = directory.activeSessionCount(realm, c.getId());
            String last = null;
            List<EventRepresentation> ev = realm.getEvents(List.of("LOGIN"), clientId, null, null, null, null, 0, 1);
            if (!ev.isEmpty()) {
                last = Instant.ofEpochMilli(ev.get(0).getTime()).toString();
            }
            List<String> permissions = realm.clients().get(c.getId()).roles().list().stream()
                    .map(r -> clientId + ":" + r.getName()).sorted().toList();
            return new ClientDashboard(clientId, c.getName() != null ? c.getName() : clientId, c.getDescription(),
                    people.size(), sessions, last, permissions);
        });
    }

    /**
     * {@code GET /admin/clients/{id}/users}: everyone holding one of the client's roles or named on its
     * access policy, with their client roles, last login to this client and account status.
     */
    public List<ClientUser> clientUsers(String clientId) {
        return executors.serviceAccount().call(realm -> {
            ClientRepresentation c = directory.requireClient(realm, clientId);
            Map<String, UserRepresentation> users = new LinkedHashMap<>();
            Map<String, List<String>> roles = new HashMap<>();
            for (RoleRepresentation role : realm.clients().get(c.getId()).roles().list()) {
                int first = 0;
                while (true) {
                    List<UserRepresentation> page = realm.clients().get(c.getId()).roles().get(role.getName())
                            .getUserMembers(first, 100);
                    for (UserRepresentation u : page) {
                        users.putIfAbsent(u.getId(), u);
                        roles.computeIfAbsent(u.getId(), k -> new ArrayList<>()).add(role.getName());
                    }
                    if (page.size() < 100) {
                        break;
                    }
                    first += 100;
                }
            }
            ClientAssignment a = ClientAssignment.fromPolicies(policies.read(realm, c.getId()));
            for (String uid : a.userIds()) {
                if (!users.containsKey(uid)) {
                    directory.getUser(realm, uid).ifPresent(u -> users.put(uid, u));
                }
            }
            Map<String, Long> lastLogin = new HashMap<>();
            for (EventRepresentation e : realm.getEvents(List.of("LOGIN"), clientId, null, null, null, null, 0, 1000)) {
                if (e.getUserId() != null) {
                    lastLogin.merge(e.getUserId(), e.getTime(), Math::max);
                }
            }
            List<ClientUser> out = new ArrayList<>();
            for (UserRepresentation u : users.values()) {
                Long t = lastLogin.get(u.getId());
                List<String> r = new ArrayList<>(roles.getOrDefault(u.getId(), List.of()));
                r.sort(String::compareTo);
                out.add(new ClientUser(u.getId(), u.getUsername(), u.getEmail(), r,
                        t == null ? null : Instant.ofEpochMilli(t).toString(),
                        Boolean.FALSE.equals(u.isEnabled()) ? "disabled" : "active"));
            }
            out.sort((x, y) -> String.valueOf(x.username()).compareToIgnoreCase(String.valueOf(y.username())));
            return out;
        });
    }

    /** {@code POST /admin/clients/{id}/users/{userId}/roles}: assign client roles (unknown role → 400). */
    public Map<String, Object> assignClientRoles(String clientId, String userId, List<String> roleNames,
                                                 CallerPrincipal caller, String ip) {
        if (roleNames == null || roleNames.isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "Send at least one role.");
        }
        MfaService.refuseExemptRole(roleNames, "/admin/clients/{client_id}/users/{user_id}/roles");
        return executors.serviceAccount().call(realm -> {
            ClientRepresentation c = directory.requireClient(realm, clientId);
            UserRepresentation user = directory.getUser(realm, userId)
                    .orElseThrow(() -> ApiException.notFound("User not found"));
            Map<String, RoleRepresentation> available = new HashMap<>();
            realm.clients().get(c.getId()).roles().list().forEach(r -> available.put(r.getName(), r));
            List<String> unknown = roleNames.stream().filter(r -> !available.containsKey(r)).toList();
            if (!unknown.isEmpty()) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                        "Unknown role(s) on client '" + clientId + "': " + unknown, Map.of("unknown_roles", unknown));
            }
            List<RoleRepresentation> reps = roleNames.stream().distinct().map(available::get).toList();
            realm.users().get(userId).roles().clientLevel(c.getId()).add(reps);
            String institution = user.getAttributes() == null || user.getAttributes().get("institution") == null
                    || user.getAttributes().get("institution").isEmpty() ? null
                    : user.getAttributes().get("institution").get(0).toLowerCase(Locale.ROOT);
            audit.emit(AuditEvent.of(AuditEventType.ADMIN_ROLE_ASSIGNED).actor(caller.username()).requestIp(ip)
                    .clientId(clientId).targetUser(userId).institution(institution)
                    .put("target_username", user.getUsername()).put("roles", roleNames)
                    .put("target_client", clientId).build());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Roles assigned successfully to user " + userId);
            out.put("roles", roleNames);
            return out;
        });
    }

    // ---- system ----------------------------------------------------------------------------

    /** {@code GET /admin/system/clients}: every client with its scope. */
    public Map<String, Object> systemClients() {
        Set<String> known = registry.knownSlugs();
        return executors.serviceAccount().call(realm -> {
            List<Map<String, Object>> clients = new ArrayList<>();
            for (ClientRepresentation c : realm.clients().findAll()) {
                ClientScope scope = ClientScope.parse(c.getClientId(), c.getAttributes(), known);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("clientId", c.getClientId());
                m.put("name", c.getName());
                m.put("description", c.getDescription());
                m.put("enabled", c.isEnabled());
                m.put("protocol", c.getProtocol());
                m.put("publicClient", c.isPublicClient());
                m.put("kind", scope.kind().code());
                m.put("owner_institution", scope.owner());
                m.put("cross_cutting", scope.crossCutting());
                clients.add(m);
            }
            clients.sort((a, b) -> String.valueOf(a.get("clientId")).compareTo(String.valueOf(b.get("clientId"))));
            return Map.of("clients", clients);
        });
    }

    /** {@code GET /admin/system/users}: one page of users and the realm total. */
    public Map<String, Object> systemUsers(int limit, int offset) {
        return executors.serviceAccount().call(realm -> {
            List<Map<String, Object>> users = new ArrayList<>();
            for (UserRepresentation u : directory.listUsers(realm, offset, limit, true)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", u.getId());
                m.put("username", u.getUsername());
                m.put("email", u.getEmail());
                m.put("firstName", u.getFirstName());
                m.put("lastName", u.getLastName());
                m.put("enabled", u.isEnabled());
                m.put("institution", KeycloakDirectory.firstAttribute(u, "institution"));
                m.put("createdTimestamp", u.getCreatedTimestamp());
                users.add(m);
            }
            Map<String, Object> pagination = new LinkedHashMap<>();
            pagination.put("limit", limit);
            pagination.put("offset", offset);
            pagination.put("total", realm.users().count());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("users", users);
            out.put("pagination", pagination);
            return out;
        });
    }

    static String slug(String code) {
        String s = code == null ? "" : code.strip().toLowerCase(Locale.ROOT);
        if (!DelegatedNamespaces.isValidSlug(s)) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "Not a valid institution code: '" + code + "'.");
        }
        return s;
    }
}
