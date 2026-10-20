package rw.gov.risa.sso.wrapper.service.institutionadmin;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.EventRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.error.KeycloakErrors;
import rw.gov.risa.sso.wrapper.policy.overview.BruteForceStatus;
import rw.gov.risa.sso.wrapper.policy.overview.LoginEvent;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;

/**
 * Every Keycloak Admin API call the delegated surface makes, each one run by the {@link KeycloakExec}
 * the caller picked (the service decides WHO executes; this class only executes). Kept as one seam so
 * the institution-admin services can be tested with a fake directory and so the credential of every call
 * is visible at the call site.
 */
@Component
public class InstitutionKeycloak {

    /** Members read per group at most (one institution's directory). */
    public static final int MAX_MEMBERS = 10_000;

    private final KeycloakDirectory directory;

    public InstitutionKeycloak(KeycloakDirectory directory) {
        this.directory = directory;
    }

    // ---- groups ----------------------------------------------------------------------------

    public Optional<GroupRepresentation> groupByPath(KeycloakExec exec, String path) {
        return exec.call(realm -> directory.groupByPath(realm, path));
    }

    /** Direct children ({@code /groups/{id}/children}). */
    public List<GroupRepresentation> groupChildren(KeycloakExec exec, String groupId) {
        return exec.call(realm -> directory.groupChildren(realm, groupId));
    }

    /** A group's name (null when it is gone or unreadable). */
    public String groupName(KeycloakExec exec, String groupId) {
        try {
            return exec.call(realm -> directory.getGroup(realm, groupId).map(GroupRepresentation::getName).orElse(null));
        } catch (WebApplicationException e) {
            return null;
        }
    }

    /** Direct members, full representations (attributes + required actions). */
    public List<UserRepresentation> groupMembers(KeycloakExec exec, String groupId) {
        return exec.call(realm -> directory.groupMembers(realm, groupId, MAX_MEMBERS));
    }

    // ---- users -----------------------------------------------------------------------------

    /** The user's direct groups (with paths); 404 when the user does not exist. */
    public List<GroupRepresentation> userGroups(KeycloakExec exec, String userId) {
        try {
            return exec.call(realm -> directory.userGroups(realm, userId));
        } catch (NotFoundException e) {
            throw ApiException.notFound("User '" + userId + "' not found.");
        }
    }

    public Optional<UserRepresentation> getUser(KeycloakExec exec, String userId) {
        return exec.call(realm -> directory.getUser(realm, userId));
    }

    /** Direct realm-role names of a user (empty when unreadable). */
    public List<String> userRealmRoles(KeycloakExec exec, String userId) {
        try {
            return exec.call(realm -> directory.userRealmRoles(realm, userId));
        } catch (WebApplicationException e) {
            return List.of();
        }
    }

    /** Creates the user; returns its id. 409 {@code conflict} for a duplicate username/email. */
    public String createUser(KeycloakExec exec, UserRepresentation user) {
        return exec.call(realm -> {
            try (Response r = realm.users().create(user)) {
                if (r.getStatus() == 409) {
                    throw ApiException.conflict(ErrorCodes.CONFLICT, "User with this username or email already exists",
                            Map.of());
                }
                if (r.getStatus() >= 300) {
                    String body = r.hasEntity() ? r.readEntity(String.class) : "";
                    throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                            "Failed to create user: Keycloak answered " + r.getStatus() + (body.isBlank() ? "" : " " + body));
                }
                return CreatedResponseUtil.getCreatedId(r);
            }
        });
    }

    public void joinGroup(KeycloakExec exec, String userId, String groupId) {
        exec.run(realm -> realm.users().get(userId).joinGroup(groupId));
    }

    public void updateUser(KeycloakExec exec, String userId, UserRepresentation user) {
        exec.run(realm -> {
            try {
                realm.users().get(userId).update(user);
            } catch (WebApplicationException e) {
                if (KeycloakErrors.status(e) == 409) {
                    throw ApiException.conflict(ErrorCodes.CONFLICT, "User with this username or email already exists",
                            Map.of());
                }
                throw e;
            }
        });
    }

    /** Ends every session of the user. */
    public void logout(KeycloakExec exec, String userId) {
        exec.run(realm -> realm.users().get(userId).logout());
    }

    // ---- roles -----------------------------------------------------------------------------

    /** Every realm role (a catalogue read). */
    public List<RoleRepresentation> realmRoles(KeycloakExec exec) {
        return exec.call(realm -> realm.roles().list());
    }

    public void addRealmRoles(KeycloakExec exec, String userId, List<RoleRepresentation> roles) {
        exec.run(realm -> realm.users().get(userId).roles().realmLevel().add(roles));
    }

    public void removeRealmRoles(KeycloakExec exec, String userId, List<RoleRepresentation> roles) {
        exec.run(realm -> realm.users().get(userId).roles().realmLevel().remove(roles));
    }

    // ---- clients ---------------------------------------------------------------------------

    /** Creates the client and returns it as stored. 409 {@code conflict} when the client-id is taken. */
    public ClientRepresentation createClient(KeycloakExec exec, ClientRepresentation client) {
        return exec.call(realm -> {
            String id;
            try (Response r = realm.clients().create(client)) {
                if (r.getStatus() == 409) {
                    throw ApiException.conflict(ErrorCodes.CONFLICT,
                            "Client '" + client.getClientId() + "' already exists.", Map.of());
                }
                if (r.getStatus() >= 300) {
                    throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                            "Failed to create client '" + client.getClientId() + "'.");
                }
                id = CreatedResponseUtil.getCreatedId(r);
            }
            return realm.clients().get(id).toRepresentation();
        });
    }

    public Optional<ClientRepresentation> findClient(KeycloakExec exec, String clientId) {
        return exec.call(realm -> directory.findClient(realm, clientId));
    }

    /** The secret of a confidential client (400 for a public one). */
    public String clientSecret(KeycloakExec exec, String clientId) {
        return exec.call(realm -> {
            ClientRepresentation c = directory.requireClient(realm, clientId);
            if (Boolean.TRUE.equals(c.isPublicClient())) {
                throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                        "Client '" + clientId + "' is a public client and has no secret.");
            }
            CredentialRepresentation secret = realm.clients().get(c.getId()).getSecret();
            if (secret == null || secret.getValue() == null) {
                throw ApiException.notFound("Client '" + clientId + "' has no secret.");
            }
            return secret.getValue();
        });
    }

    // ---- realm-wide reads for the overview -------------------------------------------------

    /** LOGIN / LOGIN_ERROR events from {@code days} days ago, at most {@code max}. */
    public List<LoginEvent> loginEvents(KeycloakExec exec, int days, int max) {
        String from = LocalDate.now(ZoneOffset.UTC).minusDays(days).toString();
        return exec.call(realm -> {
            List<EventRepresentation> events = realm.getEvents(List.of("LOGIN", "LOGIN_ERROR"), null, null, from, null,
                    null, 0, max);
            List<LoginEvent> out = new ArrayList<>(events.size());
            for (EventRepresentation e : events) {
                out.add(new LoginEvent(e.getType(), e.getUserId(), e.getTime(), e.getError()));
            }
            return out;
        });
    }

    /** The brute-force detector's view of one user; null when unreadable. */
    public BruteForceStatus bruteForce(KeycloakExec exec, String userId) {
        try {
            Map<String, Object> s = exec.call(realm -> realm.attackDetection().bruteForceUserStatus(userId));
            if (s == null) {
                return null;
            }
            Object failures = s.get("numFailures");
            return new BruteForceStatus(Boolean.TRUE.equals(s.get("disabled")),
                    failures instanceof Number n ? n.intValue() : null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** clientId -> active sessions ({@code client-session-stats}). */
    public Map<String, Integer> clientSessions(KeycloakExec exec) {
        List<Map<String, String>> stats = exec.call(realm -> realm.getClientSessionStats());
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map<String, String> s : stats) {
            String id = s.get("clientId");
            if (id == null || id.isEmpty()) {
                continue;
            }
            int active;
            try {
                active = Integer.parseInt(String.valueOf(s.getOrDefault("active", "0")));
            } catch (NumberFormatException e) {
                active = 0;
            }
            out.put(id, active);
        }
        return out;
    }
}
