package rw.gov.risa.sso.wrapper.service.platform;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.keycloak.admin.client.resource.AuthorizationResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.GroupPolicyRepresentation;
import org.keycloak.representations.idm.authorization.Logic;
import org.keycloak.representations.idm.authorization.PolicyRepresentation;
import org.keycloak.representations.idm.authorization.RolePolicyRepresentation;
import org.keycloak.representations.idm.authorization.ScopeRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.audit.AuditScope;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessDecision;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessState;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites;
import rw.gov.risa.sso.wrapper.policy.clientaccess.UserAccessChange;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScopeKind;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScopes;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;
import rw.gov.risa.sso.wrapper.service.provisioning.AccessChangeNotifier;

/**
 * {@code /authorization/**} (port of {@code api/authorization.py}): raw Keycloak Authorization
 * Services CRUD on a client's resource server (scopes, policies; by client uuid), the per-client login
 * access assignment (by clientId; {@code sso-access-*} policies via {@link ClientAccessService}), and one
 * person's access across every client — read with the gate's own rule, changed one (person, client)
 * cell at a time with an {@code expected_state} guard. Grants and revocations are audited per
 * (user, client) and the person is emailed when the gate's answer actually changed.
 */
@Service
public class AuthorizationAdminService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationAdminService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlatformSupport support;
    private final ClientAccessService access;
    private final AccessChangeNotifier notifier;

    public AuthorizationAdminService(PlatformSupport support, ClientAccessService access,
                                     AccessChangeNotifier notifier) {
        this.support = support;
        this.access = access;
        this.notifier = notifier;
    }

    /** The reference {@code Policy} body. */
    public record PolicySpec(String id, String name, String description, String type, String logic,
                             String decisionStrategy, List<Map<String, Object>> roles,
                             List<Map<String, Object>> groups) {
    }

    /** The reference {@code Scope} body. */
    public record ScopeSpec(String name, String displayName, String iconUri) {
    }

    // ---- lookups ---------------------------------------------------------------------------

    public Map<String, String> clientUuid(String clientId) {
        ClientRepresentation c = access.client(clientId);
        return Map.of("client_uuid", c.getId());
    }

    private <T> T onAuthz(String clientUuid, java.util.function.BiFunction<RealmResource, AuthorizationResource, T> work) {
        return support.sa().call(realm -> {
            ClientRepresentation c = support.requireClient(realm, clientUuid);
            return work.apply(realm, realm.clients().get(c.getId()).authorization());
        });
    }

    // ---- authorization scopes --------------------------------------------------------------

    public List<ScopeRepresentation> scopes(String clientUuid) {
        return onAuthz(clientUuid, (realm, authz) -> {
            try {
                return authz.scopes().scopes();
            } catch (NotFoundException e) {
                return List.of();
            }
        });
    }

    public ScopeRepresentation scope(String clientUuid, String name) {
        return scopes(clientUuid).stream().filter(s -> name.equals(s.getName())).findFirst()
                .orElseThrow(() -> ApiException.notFound("Scope with name '" + name + "' not found"));
    }

    public Map<String, Object> createScope(String clientUuid, ScopeSpec spec, CallerPrincipal caller) {
        Map<String, Object> created = onAuthz(clientUuid, (realm, authz) -> {
            try (Response r = authz.scopes().create(scopeRep(null, spec))) {
                return written(r, "create authorization scope");
            }
        });
        auditAuthz(caller, clientUuid, "authz_scope_created", Map.of("scope_name", spec.name()));
        return created;
    }

    public void updateScope(String clientUuid, String name, ScopeSpec spec, CallerPrincipal caller) {
        ScopeRepresentation existing = scope(clientUuid, name);
        onAuthz(clientUuid, (realm, authz) -> {
            authz.scopes().scope(existing.getId()).update(scopeRep(existing.getId(), spec));
            return null;
        });
        auditAuthz(caller, clientUuid, "authz_scope_updated", Map.of("scope_name", name));
    }

    public void deleteScope(String clientUuid, String name, CallerPrincipal caller) {
        ScopeRepresentation existing = scope(clientUuid, name);
        onAuthz(clientUuid, (realm, authz) -> {
            authz.scopes().scope(existing.getId()).remove();
            return null;
        });
        auditAuthz(caller, clientUuid, "authz_scope_deleted", Map.of("scope_name", name));
    }

    private static ScopeRepresentation scopeRep(String id, ScopeSpec spec) {
        ScopeRepresentation s = new ScopeRepresentation();
        s.setId(id);
        s.setName(spec.name());
        s.setDisplayName(spec.displayName());
        s.setIconUri(spec.iconUri());
        return s;
    }

    // ---- authorization policies ------------------------------------------------------------

    /** Every policy; empty when authorization services are not enabled on the client. */
    public List<PolicyRepresentation> policies(String clientUuid) {
        return onAuthz(clientUuid, (realm, authz) -> {
            try {
                return authz.policies().policies();
            } catch (NotFoundException e) {
                return List.of();
            }
        });
    }

    public Map<String, Object> createPolicy(String clientUuid, PolicySpec spec, CallerPrincipal caller) {
        Map<String, Object> created = onAuthz(clientUuid, (realm, authz) -> {
            String type = type(spec);
            Response r = switch (type) {
                case "role" -> authz.policies().role().create(rolePolicy(spec));
                case "group" -> authz.policies().group().create(groupPolicy(spec));
                default -> authz.policies().create(genericPolicy(spec));
            };
            try (r) {
                return written(r, "create authorization policy");
            }
        });
        auditPolicy(caller, clientUuid, "created", spec.name(), type(spec));
        return created;
    }

    public void updatePolicy(String clientUuid, PolicySpec spec, CallerPrincipal caller) {
        if (spec.id() == null || spec.id().isBlank()) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "Policy ID is required for update");
        }
        onAuthz(clientUuid, (realm, authz) -> {
            switch (type(spec)) {
                case "role" -> authz.policies().role().findById(spec.id()).update(rolePolicy(spec));
                case "group" -> authz.policies().group().findById(spec.id()).update(groupPolicy(spec));
                default -> authz.policies().policy(spec.id()).update(genericPolicy(spec));
            }
            return null;
        });
        auditPolicy(caller, clientUuid, "updated", spec.name(), type(spec));
    }

    public void deletePolicy(String clientUuid, String policyId, CallerPrincipal caller) {
        onAuthz(clientUuid, (realm, authz) -> {
            authz.policies().policy(policyId).remove();
            return null;
        });
        auditPolicy(caller, clientUuid, "deleted", policyId, null);
    }

    private static String type(PolicySpec spec) {
        return spec.type() == null || spec.type().isBlank() ? "role" : spec.type();
    }

    private static <P extends org.keycloak.representations.idm.authorization.AbstractPolicyRepresentation> P common(
            P p, PolicySpec spec) {
        p.setId(spec.id());
        p.setName(spec.name());
        p.setDescription(spec.description());
        p.setLogic(spec.logic() == null ? Logic.POSITIVE : Logic.valueOf(spec.logic()));
        p.setDecisionStrategy(spec.decisionStrategy() == null ? DecisionStrategy.UNANIMOUS
                : DecisionStrategy.valueOf(spec.decisionStrategy()));
        return p;
    }

    private static RolePolicyRepresentation rolePolicy(PolicySpec spec) {
        RolePolicyRepresentation p = common(new RolePolicyRepresentation(), spec);
        if (spec.roles() != null) {
            for (Map<String, Object> r : spec.roles()) {
                Object id = r.get("id");
                if (id != null) {
                    p.addRole(id.toString(), Boolean.TRUE.equals(r.get("required")));
                }
            }
        }
        return p;
    }

    private static GroupPolicyRepresentation groupPolicy(PolicySpec spec) {
        GroupPolicyRepresentation p = common(new GroupPolicyRepresentation(), spec);
        if (spec.groups() != null) {
            for (Map<String, Object> g : spec.groups()) {
                boolean extend = Boolean.TRUE.equals(g.get("extendChildren"));
                if (g.get("id") != null) {
                    p.addGroup(g.get("id").toString(), extend);
                } else if (g.get("path") != null) {
                    p.addGroupPath(g.get("path").toString(), extend);
                }
            }
        }
        return p;
    }

    private static PolicyRepresentation genericPolicy(PolicySpec spec) {
        PolicyRepresentation p = common(new PolicyRepresentation(), spec);
        p.setType(type(spec));
        return p;
    }

    /** A write Keycloak did not accept is never reported with a 2xx (reference {@code _require_written}). */
    private static Map<String, Object> written(Response r, String action) {
        int status = r.getStatus();
        String body = r.hasEntity() ? r.readEntity(String.class) : "";
        if (status >= 200 && status < 300) {
            try {
                return body == null || body.isBlank() ? Map.of()
                        : JSON.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() { });
            } catch (Exception e) {
                return Map.of();
            }
        }
        String message = "Failed to " + action + ": " + body;
        switch (status) {
            case 400 -> throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, message);
            case 404 -> throw ApiException.notFound(message);
            case 409 -> throw ApiException.conflict(ErrorCodes.CONFLICT, message, Map.of());
            case 403 -> throw ApiException.forbidden(ErrorCodes.FORBIDDEN, message);
            default -> throw ApiException.badGateway(ErrorCodes.KEYCLOAK_ERROR, message);
        }
    }

    private void auditAuthz(CallerPrincipal caller, String client, String action, Map<String, Object> extra) {
        Map<String, Object> target = support.clientTarget(client);
        support.emit(support.event(AuditEventType.ADMIN_CLIENT_UPDATED, caller)
                .clientId((String) target.get("target_client")).putAll(target).put("action", action).putAll(extra));
    }

    private void auditPolicy(CallerPrincipal caller, String client, String action, String policy, String type) {
        Map<String, Object> target = support.clientTarget(client);
        support.emit(support.event(AuditEventType.ACCESS_POLICY_UPDATED, caller)
                .clientId((String) target.get("target_client")).putAll(target)
                .put("action", "authz_policy_" + action).put("policy", policy).put("policy_type", type));
    }

    // ---- per-client access assignment ------------------------------------------------------

    /**
     * {@code GET /authorization/clients/{clientId}/access}: who the client's positive policies admit,
     * plus {@code denied_users} (the deny-list, which beats every grant).
     */
    public Map<String, Object> clientAccess(String clientId) {
        ClientAccessViews.AssignmentView v = access.describeAssignment(support.sa(), clientId);
        return assignmentBody(v);
    }

    private Map<String, Object> assignmentBody(ClientAccessViews.AssignmentView v) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client_id", v.clientId());
        out.put("enforced", v.enforced());
        out.put("users", v.users().stream().map(u -> userRef(u.id(), u.name())).toList());
        out.put("groups", v.groups().stream().map(g -> groupRef(g.id(), g.name())).toList());
        List<Map<String, Object>> denied = new ArrayList<>();
        if (!v.deniedUserIds().isEmpty()) {
            support.sa().run(realm -> {
                for (String uid : v.deniedUserIds()) {
                    String name = null;
                    try {
                        name = support.directory().getUser(realm, uid).map(UserRepresentation::getUsername).orElse(null);
                    } catch (WebApplicationException e) {
                        // a label only
                    }
                    denied.add(userRef(uid, name));
                }
            });
        }
        out.put("denied_users", denied);
        return out;
    }

    private static Map<String, Object> userRef(String id, String username) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("username", username);
        return m;
    }

    private static Map<String, Object> groupRef(String id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    /**
     * {@code PUT /authorization/clients/{clientId}/access}: replace the allow lists (empty = the
     * default stance). An institution group outside the client's scope is refused (400). Named users
     * have their own denial lifted. Audited with what was added and removed.
     */
    public Map<String, Object> setClientAccess(String clientId, List<String> userIds, List<String> groupIds,
                                               CallerPrincipal caller) {
        ClientRepresentation client = access.client(clientId);
        assertGroupsWithinScope(client, groupIds);
        ClientAccessViews.AssignmentView before = access.describeAssignment(support.sa(), clientId);
        ClientAccessViews.AssignmentView after = access.setAssignments(support.sa(), clientId, userIds, groupIds);
        List<String> beforeUsers = before.users().stream().map(ClientAccessViews.NamedRef::id).toList();
        List<String> beforeGroups = before.groups().stream().map(ClientAccessViews.NamedRef::id).toList();
        support.emit(support.event(AuditEventType.ACCESS_POLICY_UPDATED, caller).clientId(clientId)
                .putAll(support.clientTarget(client)).put("action", "client_access_set")
                .put("users_added", userIds.stream().filter(u -> !beforeUsers.contains(u)).toList())
                .put("users_removed", beforeUsers.stream().filter(u -> !userIds.contains(u)).toList())
                .put("groups_added", groupIds.stream().filter(g -> !beforeGroups.contains(g)).toList())
                .put("groups_removed", beforeGroups.stream().filter(g -> !groupIds.contains(g)).toList()));
        return assignmentBody(after);
    }

    /**
     * Refuse assigning an institution's group to a client its scope does not cover. Unscoped
     * (platform) clients and non-institution groups are left alone.
     */
    void assertGroupsWithinScope(ClientRepresentation client, List<String> groupIds) {
        if (groupIds.isEmpty()) {
            return;
        }
        ClientScope scope = support.scopes().scopeOf(client);
        if (!scope.isScoped()) {
            return;
        }
        List<String> names = support.sa().call(realm -> {
            List<String> out = new ArrayList<>();
            for (String gid : groupIds) {
                try {
                    support.directory().getGroup(realm, gid).map(GroupRepresentation::getName).ifPresent(out::add);
                } catch (WebApplicationException e) {
                    // a bad group id is the assignment call's error to report
                }
            }
            return out;
        });
        List<String> offending = ClientScopes.institutionsOutsideScope(scope, names, support.scopes().knownSlugs());
        if (!offending.isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                    ClientScopes.outsideScopeMessage(client.getClientId(), scope, offending));
        }
    }

    // ---- one person's access ---------------------------------------------------------------

    public ClientAccessViews.UserAccessView userClientAccess(String userId) {
        return access.userClientAccess(support.sa(), userId);
    }

    /**
     * {@code POST /authorization/users/{userId}/client-access/{clientId}}. {@code action}:
     * {@code grant}, {@code revoke}, or {@code restore} (a grant on a {@code revoked} cell — the console's
     * name for lifting a denial). 409 with {@code error} = {@code state_changed} | {@code not_applicable} |
     * {@code denied_by_group} and {@code details.current_state}; nothing is written then.
     */
    public Map<String, Object> changeUserAccess(String userId, String clientId, String actionWire,
                                                String expectedState, CallerPrincipal caller) {
        boolean restore = "restore".equals(actionWire);
        UserAccessChange.Action action = restore ? UserAccessChange.Action.GRANT
                : UserAccessChange.Action.fromWire(actionWire).orElseThrow(() -> ApiException.badRequest(
                ErrorCodes.INVALID_REQUEST, "action must be 'grant', 'revoke' or 'restore'"));

        UserRepresentation user = support.sa().call(realm -> support.requireUser(realm, userId));
        ClientRepresentation client = access.client(clientId);
        Map<String, String> groupNames = new LinkedHashMap<>();
        support.sa().run(realm -> support.directory().userGroups(realm, userId)
                .forEach(g -> groupNames.put(g.getId(), g.getName())));
        List<String> groupIds = new ArrayList<>(groupNames.keySet());

        AccessDecision before = access.decide(support.sa(), userId, groupIds, client);
        AccessState previous = before.state();
        String username = user.getUsername();

        Optional<UserAccessChange.Refusal> refusal = UserAccessChange.check(action, expectedState, before);
        if (refusal.isEmpty() && restore && previous != AccessState.REVOKED) {
            refusal = Optional.of(new UserAccessChange.Refusal(UserAccessChange.NOT_APPLICABLE, previous));
        }
        if (refusal.isPresent()) {
            throw conflict(refusal.get(), username, clientId, actionWire, before, groupNames);
        }

        boolean unassigned = false;
        boolean denied = false;
        boolean liftedDeny = false;
        if (action == UserAccessChange.Action.GRANT) {
            ClientAccessWrites.AdmitResult r = access.admitUser(support.sa(), clientId, userId);
            liftedDeny = r.liftedDenial();
        } else if (UserAccessChange.revokeMode(before) == UserAccessChange.RevokeMode.UNASSIGN_AND_OR_DENY) {
            ClientAccessWrites.RevokeResult r = access.revokeUsers(support.sa(), clientId,
                    List.of(new ClientAccessWrites.RevokeTarget(userId, groupIds)));
            unassigned = r.unassigned().contains(userId);
            denied = r.denied().contains(userId) || r.alreadyDenied().contains(userId);
        } else {
            denied = !access.denyUsers(support.sa(), clientId, List.of(userId)).changedUserIds().isEmpty();
        }

        AccessDecision after = access.decide(support.sa(), userId, groupIds, client);
        String institution = AuditScope.institutionOfUser(user.getAttributes());
        if (institution == null) {
            ClientScope scope = support.scopes().scopeOf(client);
            institution = scope.kind() == ClientScopeKind.INSTITUTION ? scope.owner() : null;
        }
        support.emit(support.event(action == UserAccessChange.Action.GRANT ? AuditEventType.ACCESS_GRANTED
                        : AuditEventType.ACCESS_REVOKED, caller)
                .clientId(clientId)
                .put("action", UserAccessChange.auditAction(action))
                .put("requested_action", actionWire)
                .put("channel", "platform-ui")
                .put("timestamp", Instant.now().toString())
                .targetClient(clientId)
                .targetUser(userId)
                .put("target_username", username)
                .putAll(AuditScope.institutionField(institution))
                .put("previous_state", previous.wire())
                .put("new_state", after.state().wire())
                .put("unassigned", unassigned)
                .put("denied", denied)
                .put("lifted_deny", liftedDeny));

        String notified = null;
        if (after.state() != previous) {
            // The person is told only when the gate's answer actually changed.
            String label = Mailer.appLabel(clientId, client.getName());
            Map<String, String> statuses = notifier.begin(action == UserAccessChange.Action.GRANT
                            ? AccessChangeNotifier.Kind.GRANT : AccessChangeNotifier.Kind.REVOKE,
                    institution != null ? institution : "your institution")
                    .add(AccessChangeNotifier.Recipient.of(user), clientId, label)
                    .flush();
            notified = statuses.get(userId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", ClientAccessService.accessItem(client, after, groupNames));
        out.put("previous_state", previous.wire());
        out.put("unassigned", unassigned);
        out.put("denied", denied);
        out.put("lifted_deny", liftedDeny);
        out.put("notified", notified);
        return out;
    }

    private static ApiException conflict(UserAccessChange.Refusal r, String username, String clientId, String action,
                                         AccessDecision before, Map<String, String> groupNames) {
        String state = r.currentState().wire();
        String message = switch (r.reason()) {
            case UserAccessChange.STATE_CHANGED -> "Access for '" + username + "' to '" + clientId
                    + "' changed since the page was loaded (now '" + state
                    + "'). Nothing was changed; reload and try again.";
            case UserAccessChange.DENIED_BY_GROUP -> "'" + username + "' is refused '" + clientId
                    + "' through a denied group (" + String.join(", ", before.deniedGroupIds().stream()
                    .map(g -> groupNames.get(g) == null ? g : groupNames.get(g)).toList())
                    + "). Granting them by name would not let them in.";
            default -> "Cannot " + action + " '" + clientId + "' for '" + username + "': access is '" + state + "'.";
        };
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", r.reason());
        details.put("current_state", state);
        log.info("user access change refused: {} {} {} -> {}", action, username, clientId, r.reason());
        return new ApiException(HttpStatus.CONFLICT, r.reason(), message, details);
    }
}
