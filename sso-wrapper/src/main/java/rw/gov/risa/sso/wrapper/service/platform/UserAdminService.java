package rw.gov.risa.sso.wrapper.service.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientMappingsRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.error.KeycloakErrors;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;

/**
 * {@code /users/**} (port of {@code api/user.py} + {@code user_service.py}). Service account
 * throughout. NID / phone / email writes go through {@link JoinKeyService#validateIdentifierWrite}
 * first (400 {@code identifier_malformed}, 409 {@code identifier_taken}) — on create, provision and
 * patch alike, so the platform and delegated surfaces cannot diverge.
 */
@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);
    /** Upper bound on one filtered listing (users are filtered/paged in memory, as the reference does). */
    static final int MAX_SCAN = 20_000;

    private final PlatformSupport support;
    private final KeycloakDirectory directory;
    private final JoinKeyService joinKeys;

    public UserAdminService(PlatformSupport support, JoinKeyService joinKeys) {
        this.support = support;
        this.directory = support.directory();
        this.joinKeys = joinKeys;
    }

    // ---- views (reference schema/user.py; camelCase on the wire) ---------------------------

    public record UserClientInfo(String clientId, String name, String description, boolean enabled,
                                 List<String> roles) {
    }

    public record UserAgencyInfo(String id, String name, String path) {
    }

    public record UserView(String id, String username, String email, String firstName, String lastName,
                           boolean enabled, boolean emailVerified, Long createdTimestamp,
                           Map<String, List<String>> attributes, List<UserAgencyInfo> agencies,
                           List<String> realmRoles, List<UserClientInfo> clients,
                           Map<String, List<String>> clientRoles) {
    }

    public record UserList(List<UserView> users, int total, int page, int pageSize, int totalPages) {
    }

    public record UserFilter(String clientId, String agencyId, String role, String search, Boolean enabled,
                             Boolean emailVerified) {
        boolean narrowsInMemory() {
            return clientId != null || agencyId != null || role != null || enabled != null || emailVerified != null;
        }
    }

    /** {@code POST /users/} and the common part of {@code /users/provision}. */
    public record NewUser(String username, String email, String firstName, String lastName, boolean enabled,
                          boolean emailVerified, Map<String, List<String>> attributes) {
    }

    /** {@code PATCH /users/{id}}: null = not sent. */
    public record UserChanges(String email, String firstName, String lastName, Boolean enabled, Boolean emailVerified,
                              Map<String, List<String>> attributes) {
        public List<String> sentFields() {
            List<String> out = new ArrayList<>();
            if (email != null) {
                out.add("email");
            }
            if (firstName != null) {
                out.add("firstName");
            }
            if (lastName != null) {
                out.add("lastName");
            }
            if (enabled != null) {
                out.add("enabled");
            }
            if (emailVerified != null) {
                out.add("emailVerified");
            }
            if (attributes != null) {
                out.add("attributes");
            }
            return out;
        }
    }

    // ---- listing ---------------------------------------------------------------------------

    /** {@code GET /users/}: filter, page (1-based), enrich the page only. */
    public UserList list(UserFilter f, int page, int pageSize) {
        int first = (page - 1) * pageSize;
        return support.sa().call(realm -> {
            List<UserRepresentation> pageUsers;
            int total;
            if (!f.narrowsInMemory()) {
                // Keycloak pages and counts natively.
                String search = blankToNull(f.search());
                total = search == null ? realm.users().count() : realm.users().count(search);
                pageUsers = realm.users().search(search, first, pageSize, false);
            } else {
                List<UserRepresentation> all = filtered(realm, f);
                total = all.size();
                pageUsers = first >= all.size() ? List.of() : all.subList(first, Math.min(all.size(), first + pageSize));
            }
            Enricher enricher = new Enricher(realm);
            List<UserView> users = pageUsers.stream().map(enricher::view).toList();
            int totalPages = total > 0 ? (total + pageSize - 1) / pageSize : 1;
            return new UserList(users, total, page, pageSize, totalPages);
        });
    }

    /** {@code GET /users/count}: {@code {"count": n}}; 0 when the count cannot be read (as the reference). */
    public Map<String, Object> count(String clientId, String agencyId) {
        try {
            int n = support.sa().call(realm -> {
                if (clientId != null) {
                    return usersByClient(realm, clientId).size();
                }
                if (agencyId != null) {
                    return directory.groupMembers(realm, agencyId, MAX_SCAN).size();
                }
                return realm.users().count();
            });
            return Map.of("count", n);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("users count failed: {}", e.getMessage());
            return Map.of("count", 0);
        }
    }

    private List<UserRepresentation> filtered(RealmResource realm, UserFilter f) {
        List<UserRepresentation> users;
        if (f.clientId() != null) {
            users = usersByClient(realm, f.clientId());
        } else if (f.agencyId() != null) {
            users = directory.groupMembers(realm, f.agencyId(), MAX_SCAN);
        } else {
            users = new ArrayList<>();
            String search = blankToNull(f.search());
            int first = 0;
            while (users.size() < MAX_SCAN) {
                List<UserRepresentation> batch = realm.users().search(search, first, KeycloakDirectory.PAGE, true);
                users.addAll(batch);
                if (batch.size() < KeycloakDirectory.PAGE) {
                    break;
                }
                first += batch.size();
            }
        }
        List<UserRepresentation> out = new ArrayList<>();
        Set<String> roleHolders = f.role() == null ? null : realmRoleHolders(realm, f.role());
        for (UserRepresentation u : users) {
            if (f.enabled() != null && !f.enabled().equals(Boolean.TRUE.equals(u.isEnabled()))) {
                continue;
            }
            if (f.emailVerified() != null && !f.emailVerified().equals(Boolean.TRUE.equals(u.isEmailVerified()))) {
                continue;
            }
            if (roleHolders != null && !roleHolders.contains(u.getId())) {
                continue;
            }
            out.add(u);
        }
        return out;
    }

    /** Users directly holding the realm role (empty for an unknown role). */
    private Set<String> realmRoleHolders(RealmResource realm, String role) {
        Set<String> ids = new LinkedHashSet<>();
        directory.realmRoleUsers(realm, role).forEach(u -> ids.add(u.getId()));
        return ids;
    }

    /**
     * Users with any role of the client: direct holders of each client role, plus members of groups
     * holding one. De-duplicated by id. 404 for an unknown client.
     */
    List<UserRepresentation> usersByClient(RealmResource realm, String clientId) {
        ClientRepresentation client = support.requireClient(realm, clientId);
        var clientResource = realm.clients().get(client.getId());
        Map<String, UserRepresentation> users = new LinkedHashMap<>();
        Set<String> groupsSeen = new LinkedHashSet<>();
        for (RoleRepresentation role : clientResource.roles().list()) {
            try {
                var roleResource = clientResource.roles().get(role.getName());
                int first = 0;
                while (true) {
                    List<UserRepresentation> page = roleResource.getUserMembers(false, first, KeycloakDirectory.PAGE);
                    page.forEach(u -> users.putIfAbsent(u.getId(), u));
                    if (page.size() < KeycloakDirectory.PAGE) {
                        break;
                    }
                    first += page.size();
                }
                for (GroupRepresentation g : roleResource.getRoleGroupMembers()) {
                    if (g.getId() != null && groupsSeen.add(g.getId())) {
                        directory.groupMembers(realm, g.getId(), MAX_SCAN).forEach(u -> users.putIfAbsent(u.getId(), u));
                    }
                }
            } catch (WebApplicationException e) {
                log.debug("users by client: cannot read holders of {}/{}: {}", clientId, role.getName(),
                        KeycloakErrors.status(e));
            }
        }
        return new ArrayList<>(users.values());
    }

    // ---- one user --------------------------------------------------------------------------

    public UserView get(String userId) {
        return support.sa().call(realm -> new Enricher(realm).view(support.requireUser(realm, userId)));
    }

    /** {@code POST /users/}. 409 when the username/email exists. Audited. */
    public UserView create(NewUser req, CallerPrincipal caller) {
        joinKeys.validateIdentifierWrite(true, req.email(), req.attributes(), null);
        String id = support.sa().call(realm -> createAccount(realm, req));
        UserView view = get(id);
        support.emit(support.event(AuditEventType.ADMIN_USER_CREATED, caller)
                .putAll(support.userTarget(id, null)).put("target_email", req.email()));
        return view;
    }

    /**
     * {@code POST /users/provision}: create + password + groups in one go; any failure after the
     * account exists deletes it again (rollback). A group that cannot be joined is logged, not fatal
     * (as the reference). The password is never logged or returned.
     */
    public UserView provision(NewUser req, String password, boolean temporary, List<String> groupIds,
                              CallerPrincipal caller) {
        joinKeys.validateIdentifierWrite(true, req.email(), req.attributes(), null);
        String id = support.sa().call(realm -> {
            String created = createAccount(realm, req);
            try {
                realm.users().get(created).resetPassword(password(password, temporary));
                for (String gid : groupIds) {
                    try {
                        realm.users().get(created).joinGroup(gid);
                    } catch (WebApplicationException e) {
                        log.warn("provision: could not add {} to group {}: {}", created, gid, KeycloakErrors.status(e));
                    }
                }
                return created;
            } catch (RuntimeException e) {
                log.warn("provision: rolling back user {}: {}", created, e.getClass().getSimpleName());
                try {
                    realm.users().delete(created).close();
                } catch (RuntimeException rollback) {
                    log.error("provision: rollback failed for user {}: {}", created, rollback.getMessage());
                }
                if (e instanceof ApiException api) {
                    throw api;
                }
                throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                        "Failed to provision user: " + PlatformSupport.reason(e));
            }
        });
        UserView view = get(id);
        support.emit(support.event(AuditEventType.ADMIN_USER_CREATED, caller)
                .putAll(support.userTarget(id, null)).put("target_email", req.email())
                .put("group_count", groupIds.size()).put("method", "provision"));
        return view;
    }

    private String createAccount(RealmResource realm, NewUser req) {
        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(req.username());
        rep.setEmail(req.email());
        rep.setFirstName(req.firstName());
        rep.setLastName(req.lastName());
        rep.setEnabled(req.enabled());
        rep.setEmailVerified(req.emailVerified());
        if (req.attributes() != null && !req.attributes().isEmpty()) {
            rep.setAttributes(new HashMap<>(req.attributes()));
        }
        try (Response response = realm.users().create(rep)) {
            int status = response.getStatus();
            if (status == 409) {
                throw ApiException.conflict(ErrorCodes.CONFLICT, "User with this username or email already exists",
                        Map.of());
            }
            if (status < 200 || status >= 300) {
                String msg = PlatformSupport.errorMessage(response);
                throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                        "Failed to create user: " + (msg != null ? msg : "Keycloak returned " + status));
            }
            String id = PlatformSupport.createdId(response);
            if (id == null) {
                id = directory.findUserByUsername(realm, req.username()).map(UserRepresentation::getId)
                        .orElseThrow(() -> ApiException.badGateway(ErrorCodes.KEYCLOAK_ERROR,
                                "User created but could not be read back"));
            }
            return id;
        }
    }

    /**
     * {@code PATCH /users/{id}}: partial update. {@code attributes} are MERGED (named keys set, an
     * empty value clears that key, the rest — IPPIS-sourced institution/position/… — preserved).
     * {@code enabled:false} is audited as {@code admin.user.disabled}.
     */
    public UserView update(String userId, UserChanges ch, CallerPrincipal caller) {
        joinKeys.validateIdentifierWrite(ch.email() != null, ch.email(), ch.attributes(), userId);
        support.sa().run(realm -> {
            UserRepresentation u = support.requireUser(realm, userId);
            if (ch.email() != null) {
                u.setEmail(ch.email());
            }
            if (ch.firstName() != null) {
                u.setFirstName(ch.firstName());
            }
            if (ch.lastName() != null) {
                u.setLastName(ch.lastName());
            }
            if (ch.enabled() != null) {
                u.setEnabled(ch.enabled());
            }
            if (ch.emailVerified() != null) {
                u.setEmailVerified(ch.emailVerified());
            }
            if (ch.attributes() != null) {
                u.setAttributes(mergeAttributes(u.getAttributes(), ch.attributes()));
            }
            try {
                realm.users().get(userId).update(u);
            } catch (WebApplicationException e) {
                int status = KeycloakErrors.status(e);
                if (status == 409) {
                    throw ApiException.conflict(ErrorCodes.CONFLICT,
                            KeycloakErrors.message(e, "User with this username or email already exists"), Map.of());
                }
                if (status == 400) {
                    throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                            "Failed to update user: " + KeycloakErrors.message(e, "rejected by Keycloak"));
                }
                throw e;
            }
        });
        AuditEventType type = Boolean.FALSE.equals(ch.enabled()) ? AuditEventType.ADMIN_USER_DISABLED
                : AuditEventType.ADMIN_USER_UPDATED;
        support.emit(support.event(type, caller).putAll(support.userTarget(userId, null))
                .put("fields", ch.sentFields()));
        return get(userId);
    }

    static Map<String, List<String>> mergeAttributes(Map<String, List<String>> existing,
                                                     Map<String, List<String>> changes) {
        Map<String, List<String>> merged = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        changes.forEach((key, values) -> {
            List<String> cleaned = values == null ? List.of()
                    : values.stream().filter(v -> v != null && !v.isEmpty()).toList();
            if (cleaned.isEmpty()) {
                merged.remove(key);
            } else {
                merged.put(key, cleaned);
            }
        });
        return merged;
    }

    /** {@code PUT /users/{id}/reset-password}. 400 when the realm's password policy refuses it. */
    public void resetPassword(String userId, String value, boolean temporary, CallerPrincipal caller) {
        support.sa().run(realm -> {
            support.requireUser(realm, userId);
            try {
                realm.users().get(userId).resetPassword(password(value, temporary));
            } catch (WebApplicationException e) {
                if (KeycloakErrors.status(e) == 400) {
                    throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                            "Failed to reset password: " + KeycloakErrors.message(e, "refused by the password policy"));
                }
                throw e;
            }
        });
        support.emit(support.event(AuditEventType.ADMIN_USER_PASSWORD_RESET, caller)
                .putAll(support.userTarget(userId, null)).put("action", "password_reset").put("temporary", temporary));
    }

    private static CredentialRepresentation password(String value, boolean temporary) {
        CredentialRepresentation c = new CredentialRepresentation();
        c.setType(CredentialRepresentation.PASSWORD);
        c.setValue(value);
        c.setTemporary(temporary);
        return c;
    }

    // ---- groups ----------------------------------------------------------------------------

    public List<GroupRepresentation> groups(String userId) {
        return support.sa().call(realm -> {
            support.requireUser(realm, userId);
            return directory.userGroups(realm, userId);
        });
    }

    public void addToGroup(String userId, String groupId, CallerPrincipal caller) {
        support.sa().run(realm -> membership(realm, userId, groupId, true));
        support.emit(support.event(AuditEventType.ADMIN_GROUP_MEMBER_ADDED, caller)
                .putAll(support.membershipTarget(userId, List.of(groupId))).put("group_id", groupId));
    }

    public void removeFromGroup(String userId, String groupId, CallerPrincipal caller) {
        support.sa().run(realm -> membership(realm, userId, groupId, false));
        support.emit(support.event(AuditEventType.ADMIN_GROUP_MEMBER_REMOVED, caller)
                .putAll(support.membershipTarget(userId, List.of(groupId))).put("group_id", groupId));
    }

    /** Bulk join/leave: every group is attempted; failures are reported together (400) after auditing the rest. */
    public void bulk(String userId, List<String> groupIds, boolean add, CallerPrincipal caller) {
        List<String> done = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        support.sa().run(realm -> {
            support.requireUser(realm, userId);
            for (String gid : new LinkedHashSet<>(groupIds)) {
                try {
                    membership(realm, userId, gid, add);
                    done.add(gid);
                } catch (ApiException e) {
                    errors.add("Group " + gid + ": " + e.getMessage());
                } catch (WebApplicationException e) {
                    errors.add("Group " + gid + ": " + PlatformSupport.reason(e));
                }
            }
        });
        if (!done.isEmpty()) {
            support.emit(support.event(add ? AuditEventType.ADMIN_GROUP_MEMBER_ADDED
                            : AuditEventType.ADMIN_GROUP_MEMBER_REMOVED, caller)
                    .putAll(support.membershipTarget(userId, done)).put("group_ids", done));
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "Some groups failed to be "
                    + (add ? "assigned" : "removed") + ": " + String.join("; ", errors));
        }
    }

    private void membership(RealmResource realm, String userId, String groupId, boolean add) {
        support.requireUser(realm, userId);
        support.requireGroup(realm, groupId);
        if (add) {
            realm.users().get(userId).joinGroup(groupId);
        } else {
            realm.users().get(userId).leaveGroup(groupId);
        }
    }

    // ---- sessions --------------------------------------------------------------------------

    public List<UserSessionRepresentation> sessions(String userId) {
        return support.sa().call(realm -> {
            support.requireUser(realm, userId);
            return realm.users().get(userId).getUserSessions();
        });
    }

    public void logoutAll(String userId, CallerPrincipal caller) {
        support.sa().run(realm -> {
            support.requireUser(realm, userId);
            realm.users().get(userId).logout();
        });
        support.emit(support.event(AuditEventType.SESSION_TERMINATED, caller).putAll(support.userTarget(userId, null))
                .put("scope", "all_sessions").put("triggered_by", "admin"));
    }

    /** End one session — only one of THIS user's (404 otherwise, so a session id cannot end someone else's). */
    public void logoutSession(String userId, String sessionId, CallerPrincipal caller) {
        support.sa().run(realm -> {
            support.requireUser(realm, userId);
            boolean owned = realm.users().get(userId).getUserSessions().stream()
                    .anyMatch(s -> sessionId.equals(s.getId()));
            if (!owned) {
                throw ApiException.notFound("Session '" + sessionId + "' not found for this user");
            }
            try {
                realm.deleteSession(sessionId, false);
            } catch (NotFoundException e) {
                throw ApiException.notFound("Session '" + sessionId + "' not found for this user");
            }
        });
        support.emit(support.event(AuditEventType.SESSION_TERMINATED, caller).putAll(support.userTarget(userId, null))
                .put("session_id", sessionId).put("triggered_by", "admin"));
    }

    // ---- enrichment ------------------------------------------------------------------------

    /**
     * Agencies, realm roles and client roles of a user. Client roles = the user's direct client
     * mappings plus those of their direct groups (one {@code role-mappings} read each), keyed by
     * clientId; Keycloak's internal clients are left out. A user that cannot be enriched is returned
     * with empty enrichment (as the reference).
     */
    final class Enricher {
        private final RealmResource realm;
        private Map<String, ClientRepresentation> clients;
        private final Map<String, MappingsRepresentation> groupMappings = new HashMap<>();

        Enricher(RealmResource realm) {
            this.realm = realm;
        }

        private Map<String, ClientRepresentation> clients() {
            if (clients == null) {
                clients = new HashMap<>();
                for (ClientRepresentation c : directory.listClients(realm)) {
                    clients.put(c.getClientId(), c);
                }
            }
            return clients;
        }

        UserView view(UserRepresentation u) {
            List<UserAgencyInfo> agencies = new ArrayList<>();
            List<String> realmRoles = new ArrayList<>();
            Map<String, List<String>> clientRoles = new LinkedHashMap<>();
            List<UserClientInfo> clientList = new ArrayList<>();
            try {
                List<GroupRepresentation> groups = directory.userGroups(realm, u.getId());
                for (GroupRepresentation g : groups) {
                    agencies.add(new UserAgencyInfo(g.getId(), g.getName(),
                            g.getPath() != null ? g.getPath() : "/" + g.getName()));
                }
                var roles = realm.users().get(u.getId()).roles();
                for (RoleRepresentation r : roles.realmLevel().listAll()) {
                    if (!Boolean.TRUE.equals(r.isComposite())) {
                        realmRoles.add(r.getName());
                    }
                }
                for (RoleRepresentation r : roles.realmLevel().listEffective()) {
                    if (!realmRoles.contains(r.getName()) && !r.getName().startsWith("default-roles-")) {
                        realmRoles.add(r.getName());
                    }
                }
                Map<String, Set<String>> byClient = new LinkedHashMap<>();
                collect(byClient, roles.getAll());
                for (GroupRepresentation g : groups) {
                    collect(byClient, groupMappings.computeIfAbsent(g.getId(),
                            id -> realm.groups().group(id).roles().getAll()));
                }
                byClient.forEach((clientId, names) -> {
                    if (PlatformSupport.KEYCLOAK_INTERNAL_CLIENTS.contains(clientId) || names.isEmpty()) {
                        return;
                    }
                    List<String> list = new ArrayList<>(names);
                    clientRoles.put(clientId, list);
                    ClientRepresentation c = clients().get(clientId);
                    clientList.add(new UserClientInfo(clientId, c != null && c.getName() != null ? c.getName() : clientId,
                            c == null ? null : c.getDescription(), c == null || !Boolean.FALSE.equals(c.isEnabled()), list));
                });
            } catch (RuntimeException e) {
                log.warn("could not enrich user {}: {}", u.getId(), e.getMessage());
                agencies.clear();
                realmRoles.clear();
                clientRoles.clear();
                clientList.clear();
            }
            return new UserView(u.getId(), u.getUsername(), u.getEmail(), u.getFirstName(), u.getLastName(),
                    !Boolean.FALSE.equals(u.isEnabled()), Boolean.TRUE.equals(u.isEmailVerified()),
                    u.getCreatedTimestamp(), u.getAttributes(), agencies, realmRoles, clientList, clientRoles);
        }

        private static void collect(Map<String, Set<String>> into, MappingsRepresentation m) {
            if (m == null || m.getClientMappings() == null) {
                return;
            }
            for (Map.Entry<String, ClientMappingsRepresentation> e : m.getClientMappings().entrySet()) {
                String clientId = e.getValue().getClient() != null ? e.getValue().getClient() : e.getKey();
                Set<String> names = into.computeIfAbsent(clientId, k -> new LinkedHashSet<>());
                if (e.getValue().getMappings() != null) {
                    e.getValue().getMappings().forEach(r -> names.add(r.getName()));
                }
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

}
