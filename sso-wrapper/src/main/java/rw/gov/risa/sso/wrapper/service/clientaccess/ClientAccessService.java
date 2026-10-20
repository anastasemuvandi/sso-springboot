package rw.gov.risa.sso.wrapper.service.clientaccess;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.error.KeycloakErrors;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessDecision;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessPolicy;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessReason;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessState;
import rw.gov.risa.sso.wrapper.policy.clientaccess.CanonicalPolicies;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessDecider;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessExemptions;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.AdmitManyResult;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.AdmitResult;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.DenyResult;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.InstitutionAccess;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.NamedGroup;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.RevokeResult;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.RevokeTarget;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.WithdrawResult;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientPolicies;
import rw.gov.risa.sso.wrapper.policy.clientaccess.PolicyIds;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews.AccessItem;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews.AssignmentView;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews.GrantRemoval;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews.LoginCheck;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews.NamedRef;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews.UserAccessView;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;

/**
 * Per-client login access (spec §5.4; port of {@code KeycloakAuthorizationService}). The rule is
 * {@code policy.clientaccess}; this class reads and writes the client's three {@code sso-access-*}
 * authorization policies.
 *
 * <ul>
 *   <li><b>Reads by type, not name</b> (every user/group policy counts, its logic decides allow vs deny);
 *       writes target the canonical policies by name+type. A malformed policy contributes nothing.</li>
 *   <li><b>Writes are read-modify-write ONCE per client</b>: one policy read, at most one write per
 *       canonical policy, serialised per client inside this JVM (policy writes replace whole policies).</li>
 *   <li><b>Who executes</b>: every method that touches a client's policies takes a {@link KeycloakExec}.
 *       The clientId → uuid lookup is a realm-wide catalogue query and always uses the service account
 *       (FGAP v2 has no vocabulary for it), after the caller's own scope check. For a CROSS-CUTTING
 *       client pass {@link #execFor(KeycloakExec, boolean)} — its policies are the platform's.</li>
 *   <li>No policy cache (the reference has none).</li>
 *   <li>Writes are not audited here: callers audit per (user, client) with their own context. The
 *       login gate's denial ({@link #enforceLogin}) is audited here.</li>
 * </ul>
 */
@Service
public class ClientAccessService {

    private static final Logger log = LoggerFactory.getLogger(ClientAccessService.class);

    private final KeycloakExecutors executors;
    private final KeycloakDirectory directory;
    private final AccessPolicyStore store;
    private final ClientAccessProperties props;
    private final List<String> exemptClients;
    private final AuditEmitter audit;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public ClientAccessService(KeycloakExecutors executors, KeycloakDirectory directory, AccessPolicyStore store,
                               ClientAccessProperties props, SsoProperties sso, AuditEmitter audit) {
        this.executors = executors;
        this.directory = directory;
        this.store = store;
        this.props = props;
        this.exemptClients = List.copyOf(sso.keycloak().exemptClients());
        this.audit = audit;
    }

    // ---- configuration ---------------------------------------------------------------------

    public KeycloakExec serviceAccount() {
        return executors.serviceAccount();
    }

    /**
     * The executor for one client's {@code sso-access-*} policies: a cross-cutting client's policies
     * are the platform's object (FGAP v2 cannot describe them), so they always use the service account
     * — call this only AFTER the wrapper's own scope check passed. Otherwise {@code requested}.
     */
    public KeycloakExec execFor(KeycloakExec requested, boolean crossCutting) {
        return crossCutting && requested.asCaller() ? executors.serviceAccount() : requested;
    }

    public boolean isExempt(String clientId) {
        return ClientAccessExemptions.isExempt(clientId, exemptClients);
    }

    public boolean defaultDeny() {
        return props.defaultDeny();
    }

    public List<String> exemptClients() {
        return exemptClients;
    }

    // ---- reads -----------------------------------------------------------------------------

    /** The client by clientId (service-account catalogue lookup), or 404. */
    public ClientRepresentation client(String clientId) {
        return executors.serviceAccount().call(realm -> directory.requireClient(realm, clientId));
    }

    /** Every authorization policy on the client, adapted (empty when authorization is off). */
    public List<AccessPolicy> policies(KeycloakExec exec, String clientId) {
        String uuid = client(clientId).getId();
        return exec.call(realm -> store.read(realm, uuid));
    }

    /** The client's current assignment (allows from every POSITIVE user/group policy + the deny-list). */
    public ClientAssignment assignment(KeycloakExec exec, String clientId) {
        return ClientAssignment.fromPolicies(policies(exec, clientId));
    }

    /** Users explicitly refused this client ({@code sso-access-denied-users}). */
    public List<String> deniedUserIds(KeycloakExec exec, String clientId) {
        return assignment(exec, clientId).deniedUserIds();
    }

    /** {@code get_client_assignments}: the assignment with usernames / group names resolved. */
    public AssignmentView describeAssignment(KeycloakExec exec, String clientId) {
        String uuid = client(clientId).getId();
        return exec.call(realm -> view(realm, clientId, ClientAssignment.fromPolicies(store.read(realm, uuid))));
    }

    private AssignmentView view(RealmResource realm, String clientId, ClientAssignment a) {
        List<NamedRef> users = new ArrayList<>();
        for (String uid : a.userIds()) {
            users.add(new NamedRef(uid, quietly(() -> directory.getUser(realm, uid).map(UserRepresentation::getUsername)
                    .orElse(null))));
        }
        List<NamedRef> groups = new ArrayList<>();
        for (String gid : a.groupIds()) {
            groups.add(new NamedRef(gid, groupName(realm, gid)));
        }
        return new AssignmentView(clientId, a.enforced(), users, groups, a.deniedUserIds());
    }

    // ---- decisions -------------------------------------------------------------------------

    /** {@code decide_for_user}: one person, one already-resolved client. Exempt clients read nothing. */
    public AccessDecision decide(KeycloakExec exec, String userId, Collection<String> directGroupIds,
                                 ClientRepresentation client) {
        if (isExempt(client.getClientId())) {
            return ClientAccessDecider.decide(userId, directGroupIds, ClientPolicies.NONE, true, defaultDeny());
        }
        List<AccessPolicy> policies = exec.call(realm -> store.read(realm, client.getId()));
        return ClientAccessDecider.decide(userId, directGroupIds, ClientPolicies.parse(policies), false, defaultDeny());
    }

    /**
     * Many people on ONE client, from one policy read. {@code userGroupIds}: userId → that person's
     * DIRECT group ids. Result keeps the input order.
     */
    public Map<String, AccessDecision> decideForUsers(KeycloakExec exec, String clientId,
                                                      Map<String, ? extends Collection<String>> userGroupIds) {
        boolean exempt = isExempt(clientId);
        ClientPolicies parsed = exempt ? ClientPolicies.NONE : ClientPolicies.parse(policies(exec, clientId));
        Map<String, AccessDecision> out = new LinkedHashMap<>();
        userGroupIds.forEach((uid, groups) ->
                out.put(uid, ClientAccessDecider.decide(uid, groups, parsed, exempt, defaultDeny())));
        return out;
    }

    /** {@link #decideForUsers} reading each person's direct groups first (one exec). */
    public Map<String, AccessDecision> decideForUserIds(KeycloakExec exec, String clientId, Collection<String> userIds) {
        return decideForUsers(exec, clientId, directGroups(exec, userIds));
    }

    /**
     * One person across every client except Keycloak's built-ins (the platform user page). A client
     * whose policies cannot be read is reported {@code unknown} with the error; the rest are answered.
     * 404 when the user does not exist.
     */
    public UserAccessView userClientAccess(KeycloakExec exec, String userId) {
        List<ClientRepresentation> clients = executors.serviceAccount().call(directory::listClients).stream()
                .filter(c -> !ClientAccessExemptions.isKeycloakBuiltIn(c.getClientId()))
                .toList();
        return exec.call(realm -> {
            UserRepresentation user = directory.getUser(realm, userId)
                    .orElseThrow(() -> ApiException.notFound("User not found"));
            Map<String, String> groupNames = new LinkedHashMap<>();
            for (GroupRepresentation g : directory.userGroups(realm, userId)) {
                if (g.getId() != null) {
                    groupNames.put(g.getId(), g.getName());
                }
            }
            List<AccessItem> items = new ArrayList<>();
            for (ClientRepresentation c : clients) {
                try {
                    AccessDecision d = isExempt(c.getClientId())
                            ? ClientAccessDecider.decide(userId, groupNames.keySet(), ClientPolicies.NONE, true,
                                    defaultDeny())
                            : ClientAccessDecider.decide(userId, groupNames.keySet(),
                                    ClientPolicies.parse(store.read(realm, c.getId())), false, defaultDeny());
                    items.add(accessItem(c, d, groupNames));
                } catch (WebApplicationException | ApiException | ProcessingException e) {
                    String error = e instanceof WebApplicationException w
                            ? "Keycloak returned " + KeycloakErrors.status(w) : e.getMessage();
                    log.warn("user access view: cannot read {}: {}", c.getClientId(), error);
                    items.add(new AccessItem(c.getClientId(), c.getId(), c.getName(), AccessState.UNKNOWN.wire(), null,
                            null, false, List.of(), false, false, List.of(), error));
                }
            }
            items.sort(Comparator.comparing((AccessItem i) -> AccessState.EXEMPT.wire().equals(i.state()))
                    .thenComparing(i -> i.clientId() == null ? "" : i.clientId()));
            List<NamedRef> groups = groupNames.entrySet().stream().map(e -> new NamedRef(e.getKey(), e.getValue()))
                    .toList();
            return new UserAccessView(userId, user.getUsername(), groups, items);
        });
    }

    /** One row of the per-user view for a decision already made. */
    public static AccessItem accessItem(ClientRepresentation c, AccessDecision d, Map<String, String> groupNames) {
        Function<List<String>, List<NamedRef>> named = ids -> ids.stream()
                .map(id -> new NamedRef(id, groupNames.get(id))).toList();
        return new AccessItem(c.getClientId(), c.getId(), c.getName(), d.state().wire(), d.hasAccess(),
                d.reason().wire(), d.direct(), named.apply(d.viaGroupIds()), d.denied(), d.deniedByName(),
                named.apply(d.deniedGroupIds()), null);
    }

    /**
     * The token gate's access check (service account; {@code validate_user_client_access}). Never
     * throws for a refusal; rethrows only when the wrapper itself cannot work (Keycloak unreachable,
     * or the service account lacks permission → 503).
     */
    public LoginCheck checkLogin(String username, String clientId) {
        if (isExempt(clientId)) {
            return new LoginCheck(true, null, true, List.of(), List.of(),
                    "Access granted: client '" + clientId + "' is exempt from per-client access enforcement.");
        }
        try {
            return executors.serviceAccount().call(realm -> checkLogin(realm, username, clientId));
        } catch (ApiException e) {
            if (e.status() == HttpStatus.SERVICE_UNAVAILABLE) {
                throw e;
            }
            return new LoginCheck(false, null, false, List.of(), List.of(), e.getMessage());
        } catch (ProcessingException e) {
            throw ApiException.unavailable(ErrorCodes.UPSTREAM_UNAVAILABLE, "Identity provider unreachable");
        } catch (RuntimeException e) {
            return new LoginCheck(false, null, false, List.of(), List.of(), "Error validating access: " + e.getMessage());
        }
    }

    private LoginCheck checkLogin(RealmResource realm, String username, String clientId) {
        UserRepresentation user;
        try {
            user = directory.findUserByUsername(realm, username)
                    .orElseThrow(() -> ApiException.notFound("User '" + username + "' not found"));
        } catch (WebApplicationException e) {
            if (KeycloakErrors.status(e) == 403) {
                throw ApiException.unavailable(ErrorCodes.UPSTREAM_UNAVAILABLE, "SSO service account lacks permission "
                        + "to query users. Please configure service account roles in Keycloak.");
            }
            throw e;
        }
        List<GroupRepresentation> groups = directory.userGroups(realm, user.getId());
        Set<String> groupIds = new LinkedHashSet<>();
        List<String> groupNames = new ArrayList<>();
        for (GroupRepresentation g : groups) {
            groupIds.add(g.getId());
            groupNames.add(g.getName());
        }
        ClientRepresentation client = directory.requireClient(realm, clientId);
        List<AccessPolicy> policies = store.read(realm, client.getId());
        AccessDecision decision = ClientAccessDecider.decide(user.getId(), groupIds, ClientPolicies.parse(policies),
                false, defaultDeny());

        if (decision.reason() == AccessReason.NO_POLICY) {
            String message = decision.hasAccess()
                    ? "Access granted to user '" + username + "' for client '" + clientId
                    + "' as no access policy is configured."
                    : "Access denied. Client '" + clientId + "' has no access assignment configured and the SSO is "
                    + "in default-deny mode. Assign user '" + username + "' (or one of their groups) to this client "
                    + "to grant access.";
            return new LoginCheck(decision.hasAccess(), decision.reason(), false, groupNames, List.of(), message);
        }
        List<String> required = new ArrayList<>();
        for (AccessPolicy p : policies) {
            if (!p.isGroupPolicy()) {
                continue;
            }
            for (String gid : PolicyIds.groupIds(p.config().get("groups"))) {
                String name = groupName(realm, gid);
                if (name != null && !required.contains(name)) {
                    required.add(name);
                }
            }
        }
        String message = switch (decision.reason()) {
            case DENIED -> "Access denied. User '" + username + "' is explicitly denied access to client '"
                    + clientId + "'.";
            case ASSIGNED -> "Access granted for user '" + username + "' to client '" + clientId + "'.";
            case NOT_ASSIGNED -> "Access denied. User '" + username + "' is not assigned to client '" + clientId + "'.";
            case DENY_LIST_ONLY -> "Access granted for user '" + username + "' to client '" + clientId
                    + "' as they are not in any denied group or user policy.";
            default -> "Access " + (decision.hasAccess() ? "granted" : "denied") + " for user '" + username
                    + "' to client '" + clientId + "': the client's access policies define no members.";
        };
        return new LoginCheck(decision.hasAccess(), decision.reason(), false, groupNames, required, message);
    }

    /**
     * The gate at a token door: {@link #checkLogin}; on refusal audit {@code LOGIN_DENIED} and throw
     * 403 {@code access_denied}. {@code grantType} is recorded when given.
     */
    public LoginCheck enforceLogin(String username, String clientId, String requestIp, String grantType) {
        LoginCheck check = checkLogin(username, clientId);
        if (check.hasAccess()) {
            return check;
        }
        audit.emit(AuditEvent.of(AuditEventType.LOGIN_DENIED).denied().actor(username).clientId(clientId)
                .requestIp(requestIp).targetClient(clientId).put("reason", check.message())
                .put("grant_type", grantType).build());
        throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED, check.message());
    }

    // ---- writes ----------------------------------------------------------------------------

    /**
     * {@code set_client_assignments}: replace the allow lists (empty deletes the policy); everyone named
     * here has their deny entry lifted; users removed by this call keep theirs.
     */
    public AssignmentView setAssignments(KeycloakExec exec, String clientId, List<String> userIds, List<String> groupIds) {
        ClientAssignment next = modify(exec, clientId,
                current -> change(ClientAccessWrites.setAssignments(current, userIds, groupIds), null)).assignment;
        return exec.call(realm -> view(realm, clientId, next));
    }

    /** Named grant for one person AND lift that person's denial. Idempotent. */
    public AdmitResult admitUser(KeycloakExec exec, String clientId, String userId) {
        return modify(exec, clientId, current -> {
            AdmitResult r = ClientAccessWrites.admitUser(current, userId);
            return change(r.assignment(), r);
        }).result;
    }

    /** {@link #admitUser} for many people in one write. */
    public AdmitManyResult admitUsers(KeycloakExec exec, String clientId, Collection<String> userIds) {
        return modify(exec, clientId, current -> {
            AdmitManyResult r = ClientAccessWrites.admitUsers(current, userIds);
            return change(r.assignment(), r);
        }).result;
    }

    /** Admit a group (an institution's): additive, never replaces other institutions' groups. */
    public ClientAssignment grantGroup(KeycloakExec exec, String clientId, String groupId) {
        return modify(exec, clientId, current -> change(ClientAccessWrites.admitInstitution(current, groupId), null))
                .assignment;
    }

    /** Remove one group from {@code sso-access-groups} (nobody else's access changes). */
    public ClientAssignment ungrantGroup(KeycloakExec exec, String clientId, String groupId) {
        return modify(exec, clientId, current -> {
            List<String> groups = new ArrayList<>(current.groupIds());
            groups.remove(groupId);
            return change(current.withGroups(groups), null);
        }).assignment;
    }

    /** Add people to the deny-list; {@code changedUserIds} = newly denied. */
    public DenyResult denyUsers(KeycloakExec exec, String clientId, Collection<String> userIds) {
        return modify(exec, clientId, current -> {
            DenyResult r = ClientAccessWrites.denyUsers(current, userIds);
            return change(r.assignment(), r);
        }).result;
    }

    /** Lift deny entries ({@code allow_users_again}); {@code changedUserIds} = actually lifted. */
    public DenyResult restore(KeycloakExec exec, String clientId, Collection<String> userIds) {
        return modify(exec, clientId, current -> {
            DenyResult r = ClientAccessWrites.allowUsersAgain(current, userIds);
            return change(r.assignment(), r);
        }).result;
    }

    /**
     * Take one client away from several people in ONE pass: a named grant is removed, access through
     * an assigned group gets a deny entry (both when both apply). {@code targets} carry each user's
     * DIRECT group ids.
     */
    public RevokeResult revokeUsers(KeycloakExec exec, String clientId, List<RevokeTarget> targets) {
        return modify(exec, clientId, current -> {
            RevokeResult r = ClientAccessWrites.revokeUsers(current, targets);
            return change(r.assignment(), r);
        }).result;
    }

    /** {@link #revokeUsers(KeycloakExec, String, List)} reading each person's direct groups first. */
    public RevokeResult revokeUserIds(KeycloakExec exec, String clientId, Collection<String> userIds) {
        Map<String, List<String>> groups = directGroups(exec, userIds);
        List<RevokeTarget> targets = new ArrayList<>();
        groups.forEach((uid, g) -> targets.add(new RevokeTarget(uid, g)));
        return revokeUsers(exec, clientId, targets);
    }

    /**
     * Withdraw ONE institution's access, nobody else's: its group (by {@code groupId}, or by name
     * case-insensitively — legacy UPPERCASE groups) and every named user whose {@code institution}
     * attribute is {@code slug}. Unreadable users are left alone. Used when a scope edit removes
     * an institution and by the delegated "revoke app access".
     */
    public WithdrawResult removeInstitutionAccess(KeycloakExec exec, String clientId, String slug, String groupId) {
        String uuid = client(clientId).getId();
        synchronized (lockFor(clientId)) {
            return exec.call(realm -> {
                List<AccessPolicy> policies = store.read(realm, uuid);
                ClientAssignment current = ClientAssignment.fromPolicies(policies);
                WithdrawResult r = ClientAccessWrites.withdrawInstitution(current, namedGroups(realm, current),
                        userInstitutions(realm, current), slug, groupId);
                if (r.changed()) {
                    store.apply(realm, uuid, CanonicalPolicies.plan(policies, r.assignment()));
                }
                return r;
            });
        }
    }

    /**
     * Per institution: is its group assigned, and how many named users carry it — what a scope edit
     * reads before its 409. Every requested institution is present (zeroes when nothing).
     */
    public Map<String, InstitutionAccess> institutionAccessSummary(KeycloakExec exec, String clientId,
                                                                    Collection<String> institutions) {
        String uuid = client(clientId).getId();
        return exec.call(realm -> {
            ClientAssignment current = ClientAssignment.fromPolicies(store.read(realm, uuid));
            return ClientAccessWrites.institutionAccessSummary(namedGroups(realm, current),
                    userInstitutions(realm, current), institutions);
        });
    }

    /**
     * Leaver offboarding: remove the person's {@code sso-access-users} entry on EVERY client (service
     * account). Deny entries are kept. Clients that fail are reported, not fatal.
     */
    public GrantRemoval removeNamedGrantsEverywhere(String userId) {
        KeycloakExec sa = executors.serviceAccount();
        List<ClientRepresentation> clients = sa.call(directory::listClients);
        List<String> changed = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (ClientRepresentation c : clients) {
            if (c.getClientId() == null || isExempt(c.getClientId())
                    || ClientAccessExemptions.isKeycloakBuiltIn(c.getClientId())) {
                continue;
            }
            try {
                boolean did = modifyClient(sa, c.getClientId(), c.getId(), current -> {
                    if (!current.userIds().contains(userId)) {
                        return change(current, false);
                    }
                    List<String> users = new ArrayList<>(current.userIds());
                    users.remove(userId);
                    return change(current.withUsers(users), true);
                }).result;
                if (did) {
                    changed.add(c.getClientId());
                }
            } catch (RuntimeException e) {
                log.warn("offboarding: could not remove {} from {}: {}", userId, c.getClientId(), e.getMessage());
                failures.put(c.getClientId(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        return new GrantRemoval(userId, changed, failures);
    }

    // ---- internals -------------------------------------------------------------------------

    private record Change<R>(ClientAssignment assignment, R result) {
    }

    private static <R> Change<R> change(ClientAssignment next, R result) {
        return new Change<>(next, result);
    }

    private <R> Change<R> modify(KeycloakExec exec, String clientId, Function<ClientAssignment, Change<R>> mutation) {
        return modifyClient(exec, clientId, client(clientId).getId(), mutation);
    }

    private <R> Change<R> modifyClient(KeycloakExec exec, String clientId, String uuid,
                                       Function<ClientAssignment, Change<R>> mutation) {
        synchronized (lockFor(clientId)) {
            return exec.call(realm -> {
                List<AccessPolicy> policies = store.read(realm, uuid);
                Change<R> c = mutation.apply(ClientAssignment.fromPolicies(policies));
                store.apply(realm, uuid, CanonicalPolicies.plan(policies, c.assignment()));
                return c;
            });
        }
    }

    private Object lockFor(String clientId) {
        return locks.computeIfAbsent(clientId == null ? "" : clientId, k -> new Object());
    }

    private Map<String, List<String>> directGroups(KeycloakExec exec, Collection<String> userIds) {
        return exec.call(realm -> {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (String uid : new LinkedHashSet<>(userIds)) {
                if (uid == null || uid.isBlank()) {
                    continue;
                }
                List<String> ids = new ArrayList<>();
                try {
                    directory.userGroups(realm, uid).forEach(g -> ids.add(g.getId()));
                } catch (WebApplicationException e) {
                    log.debug("client access: cannot read groups of {}: {}", uid, KeycloakErrors.status(e));
                }
                out.put(uid, ids);
            }
            return out;
        });
    }

    private List<NamedGroup> namedGroups(RealmResource realm, ClientAssignment a) {
        List<NamedGroup> out = new ArrayList<>();
        for (String gid : a.groupIds()) {
            out.add(new NamedGroup(gid, groupName(realm, gid)));
        }
        return out;
    }

    /** userId → institution attribute values, for named users that can be read (others omitted). */
    private Map<String, List<String>> userInstitutions(RealmResource realm, ClientAssignment a) {
        Map<String, List<String>> out = new HashMap<>();
        for (String uid : a.userIds()) {
            try {
                Optional<UserRepresentation> user = directory.getUser(realm, uid);
                user.ifPresent(u -> out.put(uid, u.getAttributes() == null ? List.of()
                        : u.getAttributes().getOrDefault("institution", List.of())));
            } catch (WebApplicationException e) {
                // dangling, or not readable by this credential: leave it for the platform screen
            }
        }
        return out;
    }

    private String groupName(RealmResource realm, String gid) {
        return quietly(() -> directory.getGroup(realm, gid).map(GroupRepresentation::getName).orElse(null));
    }

    private static String quietly(java.util.function.Supplier<String> s) {
        try {
            return s.get();
        } catch (WebApplicationException | ProcessingException e) {
            return null;
        }
    }
}
