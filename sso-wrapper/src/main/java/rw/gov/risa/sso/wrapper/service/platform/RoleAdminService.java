package rw.gov.risa.sso.wrapper.service.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.representations.idm.ClientMappingsRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;

import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.error.KeycloakErrors;
import rw.gov.risa.sso.wrapper.policy.audit.AuditScope;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;

/**
 * {@code /roles/**} (port of {@code api/roles.py} + the role half of {@code keycloak_admin_service.py}).
 * Client routes take the client's uuid (reference) or its clientId. User role mappings name roles by
 * {@code name} (the role objects in the body may carry only a name); an unknown role is a 400 rather
 * than a silent no-op. {@code mfa-exempt} is never assignable here (400) — exemptions need a reason.
 */
@Service
public class RoleAdminService {

    private final PlatformSupport support;

    public RoleAdminService(PlatformSupport support) {
        this.support = support;
    }

    // ---- views (reference api/roles.py, snake_case) -----------------------------------------

    public record RoleView(String id, String name, String description, boolean composite,
                           @JsonProperty("client_role") boolean clientRole,
                           @JsonProperty("container_id") String containerId,
                           Map<String, List<String>> attributes) {
    }

    public record RoleClientInfo(String id, @JsonProperty("client_id") String clientId, String name,
                                 String description) {
    }

    public record RoleStatistics(@JsonProperty("realm_roles_count") int realmRolesCount,
                                 @JsonProperty("client_roles_count") int clientRolesCount,
                                 @JsonProperty("total_clients") int totalClients,
                                 @JsonProperty("system_roles") List<String> systemRoles,
                                 @JsonProperty("custom_roles") List<String> customRoles) {
    }

    public record UserRoleInfo(@JsonProperty("user_id") String userId,
                               @JsonProperty("realm_roles") List<RoleView> realmRoles,
                               @JsonProperty("client_roles") Map<String, List<RoleView>> clientRoles) {
    }

    /** {@code RoleRequest}. */
    public record RoleSpec(String name, String description, boolean composite, Map<String, List<String>> attributes) {
        RoleRepresentation toRep() {
            RoleRepresentation r = new RoleRepresentation();
            r.setName(name);
            r.setDescription(description);
            r.setComposite(composite);
            if (attributes != null && !attributes.isEmpty()) {
                r.setAttributes(attributes);
            }
            return r;
        }
    }

    static RoleView view(RoleRepresentation r, boolean clientRoleDefault) {
        return new RoleView(r.getId(), r.getName(), r.getDescription(), Boolean.TRUE.equals(r.isComposite()),
                r.getClientRole() == null ? clientRoleDefault : r.getClientRole(), r.getContainerId(),
                r.getAttributes());
    }

    private static Map<String, String> message(String text) {
        return Map.of("message", text);
    }

    // ---- realm roles -----------------------------------------------------------------------

    public List<RoleView> realmRoles() {
        return support.sa().call(realm -> realm.roles().list(false).stream().map(r -> view(r, false)).toList());
    }

    public Map<String, String> createRealmRole(RoleSpec spec, CallerPrincipal caller) {
        support.sa().run(realm -> create(realm.roles(), spec, "realm role"));
        support.emit(support.event(AuditEventType.ADMIN_ROLE_CREATED, caller).put("role_name", spec.name())
                .put("target", spec.name()).put("scope", "realm")
                .putAll(AuditScope.institutionField(AuditScope.institutionOfRole(spec.name()))));
        return message("Realm role '" + spec.name() + "' created successfully");
    }

    public Map<String, String> updateRealmRole(String roleName, RoleSpec spec, CallerPrincipal caller) {
        support.sa().run(realm -> update(realm.roles(), roleName, spec, "Realm role"));
        support.emit(support.event(AuditEventType.ADMIN_ROLE_UPDATED, caller).put("role_name", roleName)
                .put("new_name", spec.name()).put("target", roleName).put("scope", "realm")
                .putAll(AuditScope.institutionField(AuditScope.institutionOfRole(roleName))));
        return message("Realm role '" + roleName + "' updated successfully");
    }

    public Map<String, String> deleteRealmRole(String roleName, CallerPrincipal caller) {
        support.sa().run(realm -> delete(realm.roles(), roleName, "Realm role"));
        support.emit(support.event(AuditEventType.ADMIN_ROLE_DELETED, caller).put("role_name", roleName)
                .put("target", roleName).put("scope", "realm")
                .putAll(AuditScope.institutionField(AuditScope.institutionOfRole(roleName))));
        return message("Realm role '" + roleName + "' deleted successfully");
    }

    // ---- client roles ----------------------------------------------------------------------

    public List<RoleView> clientRoles(String client) {
        return support.sa().call(realm -> {
            ClientRepresentation c = support.requireClient(realm, client);
            return realm.clients().get(c.getId()).roles().list(false).stream().map(r -> view(r, true)).toList();
        });
    }

    public Map<String, String> createClientRole(String client, RoleSpec spec, CallerPrincipal caller) {
        ClientRepresentation c = support.sa().call(realm -> {
            ClientRepresentation found = support.requireClient(realm, client);
            create(realm.clients().get(found.getId()).roles(), spec, "client role");
            return found;
        });
        support.emit(support.event(AuditEventType.ADMIN_ROLE_CREATED, caller).clientId(c.getClientId())
                .putAll(support.clientTarget(c)).put("role_name", spec.name()).put("scope", "client")
                .put("client_id", c.getClientId()));
        return message("Client role '" + spec.name() + "' created successfully");
    }

    public Map<String, String> updateClientRole(String client, String roleName, RoleSpec spec, CallerPrincipal caller) {
        ClientRepresentation c = support.sa().call(realm -> {
            ClientRepresentation found = support.requireClient(realm, client);
            update(realm.clients().get(found.getId()).roles(), roleName, spec, "Client role");
            return found;
        });
        support.emit(support.event(AuditEventType.ADMIN_ROLE_UPDATED, caller).clientId(c.getClientId())
                .putAll(support.clientTarget(c)).put("role_name", roleName).put("new_name", spec.name())
                .put("scope", "client").put("client_id", c.getClientId()));
        return message("Client role '" + roleName + "' updated successfully");
    }

    public Map<String, String> deleteClientRole(String client, String roleName, CallerPrincipal caller) {
        ClientRepresentation c = support.sa().call(realm -> {
            ClientRepresentation found = support.requireClient(realm, client);
            delete(realm.clients().get(found.getId()).roles(), roleName, "Client role");
            return found;
        });
        support.emit(support.event(AuditEventType.ADMIN_ROLE_DELETED, caller).clientId(c.getClientId())
                .putAll(support.clientTarget(c)).put("role_name", roleName).put("scope", "client")
                .put("client_id", c.getClientId()));
        return message("Client role '" + roleName + "' deleted successfully");
    }

    private static void create(RolesResource roles, RoleSpec spec, String what) {
        try {
            roles.create(spec.toRep());
        } catch (WebApplicationException e) {
            if (KeycloakErrors.status(e) == 409) {
                throw ApiException.conflict(ErrorCodes.CONFLICT,
                        "A " + what + " named '" + spec.name() + "' already exists.", Map.of());
            }
            if (KeycloakErrors.status(e) == 400) {
                throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                        KeycloakErrors.message(e, "Failed to create " + what));
            }
            throw e;
        }
    }

    private static void update(RolesResource roles, String roleName, RoleSpec spec, String what) {
        RoleResource role = roles.get(roleName);
        RoleRepresentation current;
        try {
            current = role.toRepresentation();
        } catch (NotFoundException e) {
            throw ApiException.notFound(what + " '" + roleName + "' not found");
        }
        RoleRepresentation rep = spec.toRep();
        rep.setId(current.getId());
        try {
            role.update(rep);
        } catch (WebApplicationException e) {
            if (KeycloakErrors.status(e) == 409) {
                throw ApiException.conflict(ErrorCodes.CONFLICT, "A role named '" + spec.name() + "' already exists.",
                        Map.of());
            }
            throw e;
        }
    }

    private static void delete(RolesResource roles, String roleName, String what) {
        try {
            roles.get(roleName).remove();
        } catch (NotFoundException e) {
            throw ApiException.notFound(what + " '" + roleName + "' not found");
        }
    }

    // ---- user role mappings ----------------------------------------------------------------

    /** Direct realm roles and direct client roles per clientId (one role-mappings read). */
    public UserRoleInfo userRoles(String userId) {
        return support.sa().call(realm -> {
            support.requireUser(realm, userId);
            MappingsRepresentation m = realm.users().get(userId).roles().getAll();
            List<RoleView> realmRoles = m.getRealmMappings() == null ? List.of()
                    : m.getRealmMappings().stream().map(r -> view(r, false)).toList();
            Map<String, List<RoleView>> clientRoles = new LinkedHashMap<>();
            if (m.getClientMappings() != null) {
                for (Map.Entry<String, ClientMappingsRepresentation> e : m.getClientMappings().entrySet()) {
                    String clientId = e.getValue().getClient() != null ? e.getValue().getClient() : e.getKey();
                    if (clientId.startsWith("security-admin") || e.getValue().getMappings() == null
                            || e.getValue().getMappings().isEmpty()) {
                        continue;
                    }
                    clientRoles.put(clientId, e.getValue().getMappings().stream().map(r -> view(r, true)).toList());
                }
            }
            return new UserRoleInfo(userId, realmRoles, clientRoles);
        });
    }

    public Map<String, String> changeUserRealmRoles(String userId, List<String> roleNames, boolean add,
                                                    CallerPrincipal caller) {
        if (add) {
            MfaService.refuseExemptRole(roleNames, "the generic role endpoint");
        }
        support.sa().run(realm -> {
            support.requireUser(realm, userId);
            List<RoleRepresentation> reps = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            for (String n : new LinkedHashSet<>(roleNames)) {
                support.directory().realmRole(realm, n).ifPresentOrElse(reps::add, () -> unknown.add(n));
            }
            refuseUnknown(unknown, "realm");
            var level = realm.users().get(userId).roles().realmLevel();
            if (add) {
                level.add(reps);
            } else {
                level.remove(reps);
            }
        });
        support.emit(support.event(add ? AuditEventType.ADMIN_ROLE_ASSIGNED : AuditEventType.ADMIN_ROLE_REMOVED, caller)
                .putAll(support.userTarget(userId, AuditScope.institutionOfRoles(roleNames)))
                .put("roles", roleNames).put("scope", "realm"));
        return message("Realm roles " + (add ? "assigned to" : "removed from") + " user " + userId + " successfully");
    }

    public Map<String, String> changeUserClientRoles(String userId, String client, List<String> roleNames, boolean add,
                                                     CallerPrincipal caller) {
        ClientRepresentation c = support.sa().call(realm -> {
            support.requireUser(realm, userId);
            ClientRepresentation found = support.requireClient(realm, client);
            Map<String, RoleRepresentation> byName = new LinkedHashMap<>();
            realm.clients().get(found.getId()).roles().list().forEach(r -> byName.put(r.getName(), r));
            List<RoleRepresentation> reps = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            for (String n : new LinkedHashSet<>(roleNames)) {
                if (byName.containsKey(n)) {
                    reps.add(byName.get(n));
                } else {
                    unknown.add(n);
                }
            }
            refuseUnknown(unknown, "client");
            var level = realm.users().get(userId).roles().clientLevel(found.getId());
            if (add) {
                level.add(reps);
            } else {
                level.remove(reps);
            }
            return found;
        });
        Object owner = support.clientTarget(c).get("institution");
        support.emit(support.event(add ? AuditEventType.ADMIN_ROLE_ASSIGNED : AuditEventType.ADMIN_ROLE_REMOVED, caller)
                .clientId(c.getClientId())
                .putAll(support.userTarget(userId, owner == null ? null : owner.toString()))
                .put("target_client", c.getClientId()).put("roles", roleNames).put("scope", "client"));
        return message("Client roles " + (add ? "assigned to" : "removed from") + " user " + userId + " successfully");
    }

    private static void refuseUnknown(List<String> unknown, String kind) {
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                    "Unknown " + kind + " role(s): " + ClientAdminService.pyList(unknown));
        }
    }

    // ---- catalogue -------------------------------------------------------------------------

    public RoleStatistics statistics() {
        return support.sa().call(realm -> {
            List<RoleRepresentation> realmRoles = realm.roles().list();
            List<ClientRepresentation> clients = visibleClients(realm);
            int clientRoles = 0;
            for (ClientRepresentation c : clients) {
                try {
                    clientRoles += realm.clients().get(c.getId()).roles().list().size();
                } catch (WebApplicationException e) {
                    // a client whose roles cannot be read adds nothing
                }
            }
            return new RoleStatistics(realmRoles.size(), clientRoles, clients.size(),
                    realmRoles.stream().filter(r -> Boolean.TRUE.equals(r.isComposite())).map(RoleRepresentation::getName).toList(),
                    realmRoles.stream().filter(r -> !Boolean.TRUE.equals(r.isComposite())).map(RoleRepresentation::getName).toList());
        });
    }

    public List<RoleClientInfo> clients() {
        return support.sa().call(realm -> visibleClients(realm).stream()
                .map(c -> new RoleClientInfo(c.getId(), c.getClientId(), c.getName(), c.getDescription())).toList());
    }

    /** {@code {"client_uuid": uuid}} — null when there is no such client (the reference's answer). */
    public Map<String, String> clientUuid(String clientId) {
        String uuid = support.sa().call(realm -> support.directory().findClient(realm, clientId)
                .map(ClientRepresentation::getId).orElse(null));
        Map<String, String> out = new LinkedHashMap<>();
        out.put("client_uuid", uuid);
        return out;
    }

    private List<ClientRepresentation> visibleClients(RealmResource realm) {
        return support.directory().listClients(realm).stream()
                .filter(c -> c.getClientId() == null || !c.getClientId().startsWith("security-admin")).toList();
    }
}
