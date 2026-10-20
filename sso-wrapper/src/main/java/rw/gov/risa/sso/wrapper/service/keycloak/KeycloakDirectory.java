package rw.gov.risa.sso.wrapper.service.keycloak;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.KeycloakErrors;

/**
 * Small Keycloak Admin API helpers many features need. Every method takes the {@link RealmResource}
 * of an already-open unit of work ({@code exec.call(realm -> directory.x(realm, ...))}), so several
 * helpers share one admin client and one token. 404s become empty results; other Admin API failures
 * propagate as {@code WebApplicationException} (the global handler maps them).
 */
@Component
public class KeycloakDirectory {

    /** Page size for paged reads. */
    public static final int PAGE = 100;

    // ---- clients ---------------------------------------------------------------------------

    /** The client with this {@code clientId} (exact), or empty. Needs query-clients/view-clients. */
    public Optional<ClientRepresentation> findClient(RealmResource realm, String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        return realm.clients().findByClientId(clientId).stream()
                .filter(c -> clientId.equals(c.getClientId()))
                .findFirst();
    }

    /** {@link #findClient} or 404 {@code not_found}. */
    public ClientRepresentation requireClient(RealmResource realm, String clientId) {
        return findClient(realm, clientId)
                .orElseThrow(() -> ApiException.notFound("Client '" + clientId + "' not found."));
    }

    /** Every client in the realm, paged so a large realm is not truncated. */
    public List<ClientRepresentation> listClients(RealmResource realm) {
        List<ClientRepresentation> out = new ArrayList<>();
        int first = 0;
        while (true) {
            List<ClientRepresentation> page = realm.clients().findAll(null, null, null, first, PAGE);
            out.addAll(page);
            if (page.size() < PAGE) {
                return out;
            }
            first += PAGE;
        }
    }

    // ---- users -----------------------------------------------------------------------------

    /** Full user representation (with attributes), or empty on 404. */
    public Optional<UserRepresentation> getUser(RealmResource realm, String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(realm.users().get(userId).toRepresentation());
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    /** The account whose username is exactly {@code username} (Keycloak lower-cases usernames). */
    public Optional<UserRepresentation> findUserByUsername(RealmResource realm, String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return realm.users().searchByUsername(username, true).stream()
                .filter(u -> username.equalsIgnoreCase(u.getUsername()))
                .findFirst();
    }

    /** Accounts whose email equals {@code email} (Keycloak's exact search is case-insensitive). */
    public List<UserRepresentation> findUsersByEmail(RealmResource realm, String email, int max) {
        if (email == null || email.isBlank()) {
            return List.of();
        }
        return realm.users().search(null, null, null, email.strip(), 0, max, null, false, true);
    }

    /**
     * Accounts whose attribute {@code name} equals {@code value} ({@code GET /users?q=name:value&exact=true},
     * full representations). Values are not quoted, so pass identifiers (no spaces / colons).
     */
    public List<UserRepresentation> findUsersByAttribute(RealmResource realm, String name, String value, int max) {
        if (name == null || name.isBlank() || value == null || value.isBlank()) {
            return List.of();
        }
        return realm.users().searchByAttributes(0, max, null, true, false, name + ":" + value.strip());
    }

    /** One page of users ({@code briefRepresentation=false} when {@code full}). */
    public List<UserRepresentation> listUsers(RealmResource realm, int first, int max, boolean full) {
        return realm.users().search(null, null, null, null, first, max, null, !full);
    }

    /**
     * Walk every user page by page; {@code page} returns false to stop early. Offset paging over
     * Keycloak's username order: users created mid-walk may shift a page, so callers must be idempotent.
     */
    public void forEachUserPage(RealmResource realm, int pageSize, boolean full, Predicate<List<UserRepresentation>> page) {
        int first = 0;
        while (true) {
            List<UserRepresentation> batch = listUsers(realm, first, pageSize, full);
            if (batch.isEmpty() || !page.test(batch) || batch.size() < pageSize) {
                return;
            }
            first += batch.size();
        }
    }

    /** The user's DIRECT groups (what the access rule reads), with paths. */
    public List<GroupRepresentation> userGroups(RealmResource realm, String userId) {
        return realm.users().get(userId).groups(0, 1000, false);
    }

    /** The user's credentials (types only matter to most callers). */
    public List<CredentialRepresentation> credentials(RealmResource realm, String userId) {
        return realm.users().get(userId).credentials();
    }

    /** {@code type}s of the user's credentials, lower-cased. */
    public List<String> credentialTypes(RealmResource realm, String userId) {
        return credentials(realm, userId).stream().map(CredentialRepresentation::getType)
                .filter(t -> t != null).map(t -> t.toLowerCase(java.util.Locale.ROOT)).toList();
    }

    // ---- groups ----------------------------------------------------------------------------

    /** Group by path ({@code /minagri}, {@code /minagri/planning}), or empty. */
    public Optional<GroupRepresentation> groupByPath(RealmResource realm, String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(realm.getGroupByPath(path.startsWith("/") ? path : "/" + path));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    /** Group by id, or empty. */
    public Optional<GroupRepresentation> getGroup(RealmResource realm, String groupId) {
        try {
            return Optional.ofNullable(realm.groups().group(groupId).toRepresentation());
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * Direct children of a group from {@code /groups/{id}/children} — on Keycloak 26 a group's own
     * {@code subGroups} field is always EMPTY, so never read that.
     */
    public List<GroupRepresentation> groupChildren(RealmResource realm, String groupId) {
        List<GroupRepresentation> out = new ArrayList<>();
        int first = 0;
        while (true) {
            List<GroupRepresentation> page = realm.groups().group(groupId).getSubGroups(first, PAGE, false);
            out.addAll(page);
            if (page.size() < PAGE) {
                return out;
            }
            first += PAGE;
        }
    }

    /** {@code rootId} plus every descendant id, breadth-first (any depth). */
    public List<String> groupTreeIds(RealmResource realm, String rootId) {
        List<String> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>();
        ids.add(rootId);
        seen.add(rootId);
        queue.add(rootId);
        while (!queue.isEmpty()) {
            String next = queue.remove(0);
            for (GroupRepresentation child : groupChildren(realm, next)) {
                if (child.getId() != null && seen.add(child.getId())) {
                    ids.add(child.getId());
                    queue.add(child.getId());
                }
            }
        }
        return ids;
    }

    /** Direct members of a group (full representations), at most {@code max}; empty when the group is gone. */
    public List<UserRepresentation> groupMembers(RealmResource realm, String groupId, int max) {
        List<UserRepresentation> out = new ArrayList<>();
        int first = 0;
        try {
            while (out.size() < max) {
                int size = Math.min(PAGE, max - out.size());
                List<UserRepresentation> page = realm.groups().group(groupId).members(first, size, false);
                out.addAll(page);
                if (page.size() < size) {
                    break;
                }
                first += page.size();
            }
        } catch (NotFoundException e) {
            return List.of();
        }
        return out;
    }

    // ---- sessions --------------------------------------------------------------------------

    /** Live sessions on a client (online + offline). Needs view-clients/view-users. */
    public long activeSessionCount(RealmResource realm, String clientUuid) {
        var client = realm.clients().get(clientUuid);
        long online = number(client.getApplicationSessionCount());
        long offline;
        try {
            offline = number(client.getOfflineSessionCount());
        } catch (WebApplicationException e) {
            offline = 0;
        }
        return online + offline;
    }

    private static long number(Map<String, ? extends Number> count) {
        if (count == null) {
            return 0;
        }
        Number n = count.get("count");
        return n == null ? 0 : n.longValue();
    }

    // ---- realm roles -----------------------------------------------------------------------

    public Optional<RoleRepresentation> realmRole(RealmResource realm, String name) {
        try {
            return Optional.ofNullable(realm.roles().get(name).toRepresentation());
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    /** Creates the realm role when missing; returns it. */
    public RoleRepresentation ensureRealmRole(RealmResource realm, String name, String description) {
        Optional<RoleRepresentation> existing = realmRole(realm, name);
        if (existing.isPresent()) {
            return existing.get();
        }
        RoleRepresentation rep = new RoleRepresentation();
        rep.setName(name);
        rep.setDescription(description);
        try {
            realm.roles().create(rep);
        } catch (WebApplicationException e) {
            if (KeycloakErrors.status(e) != 409) {
                throw e;
            }
        }
        return realm.roles().get(name).toRepresentation();
    }

    /** Users DIRECTLY holding a realm role (paged); empty when the role does not exist. */
    public List<UserRepresentation> realmRoleUsers(RealmResource realm, String role) {
        List<UserRepresentation> out = new ArrayList<>();
        int first = 0;
        try {
            while (true) {
                List<UserRepresentation> page = realm.roles().get(role).getUserMembers(false, first, PAGE);
                out.addAll(page);
                if (page.size() < PAGE) {
                    return out;
                }
                first += PAGE;
            }
        } catch (NotFoundException e) {
            return List.of();
        }
    }

    /** The user's direct realm-role names. */
    public List<String> userRealmRoles(RealmResource realm, String userId) {
        return realm.users().get(userId).roles().realmLevel().listAll().stream()
                .map(RoleRepresentation::getName).toList();
    }

    /** Adds the named realm roles (unknown names are skipped); returns the names added. */
    public List<String> addRealmRoles(RealmResource realm, String userId, List<String> names) {
        List<RoleRepresentation> reps = resolveRoles(realm, names);
        if (!reps.isEmpty()) {
            realm.users().get(userId).roles().realmLevel().add(reps);
        }
        return reps.stream().map(RoleRepresentation::getName).toList();
    }

    /** Removes the named realm roles (unknown names are skipped); returns the names removed. */
    public List<String> removeRealmRoles(RealmResource realm, String userId, List<String> names) {
        List<RoleRepresentation> reps = resolveRoles(realm, names);
        if (!reps.isEmpty()) {
            realm.users().get(userId).roles().realmLevel().remove(reps);
        }
        return reps.stream().map(RoleRepresentation::getName).toList();
    }

    private List<RoleRepresentation> resolveRoles(RealmResource realm, List<String> names) {
        Map<String, RoleRepresentation> out = new LinkedHashMap<>();
        for (String n : names) {
            realmRole(realm, n).ifPresent(r -> out.put(r.getName(), r));
        }
        return new ArrayList<>(out.values());
    }

    // ---- attributes ------------------------------------------------------------------------

    /** First value of a user attribute, trimmed; null when absent/blank. */
    public static String firstAttribute(UserRepresentation user, String name) {
        if (user == null || user.getAttributes() == null) {
            return null;
        }
        List<String> values = user.getAttributes().get(name);
        if (values == null || values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
            return null;
        }
        return values.get(0).strip();
    }
}
