package rw.gov.risa.sso.wrapper.service.institutionadmin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.error.KeycloakErrors;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccessMatrix;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Action;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Channel;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Holder;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.RevokeResult;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.RevokeTarget;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.institution.DelegatedExecution;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionContextService;
import rw.gov.risa.sso.wrapper.service.institutionadmin.InstitutionAppsService.App;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;
import rw.gov.risa.sso.wrapper.service.provisioning.AccessChangeNotifier;

/**
 * Taking applications away from individual people (spec §5.13; port of {@code apps_for_users} and
 * {@code revoke_user_access}). Both console flows (client-first, user-first) end at {@link #revoke}.
 *
 * <p><b>This removes a client assignment and nothing else</b>: a named grant is removed; access through
 * a group (the institution's own) becomes a per-person entry on {@code sso-access-denied-users}, which
 * beats every allow — the person stays in the institution group, keeps their account, roles and other
 * apps. Writes are batched per client (one policy read-modify-write per client); a preview computes the
 * identical report and writes nothing; every applied (user, client) pair is audited on its own; people
 * with an account are emailed once per request about what was actually applied.
 */
@Service
public class AccessRevocationService {

    private static final Logger log = LoggerFactory.getLogger(AccessRevocationService.class);

    public static final String REASON_NOT_ASSIGNED = "not_assigned";
    public static final String REASON_ALREADY_REVOKED = "already_revoked";
    public static final String REASON_FAILED = "failed";

    /** One revoke request (see {@code RevokeRequest}). */
    public record RevokeCommand(List<String> userIds, List<String> clientIds, boolean allClients,
                                boolean confirmAllClients, boolean preview) {
    }

    private final InstitutionMembers members;
    private final InstitutionAppsService apps;
    private final AccessHolders holders;
    private final ClientAccessService access;
    private final DelegatedExecution delegated;
    private final AccessChangeNotifier notifier;
    private final AuditEmitter audit;

    public AccessRevocationService(InstitutionMembers members, InstitutionAppsService apps, AccessHolders holders,
                                   ClientAccessService access, DelegatedExecution delegated,
                                   AccessChangeNotifier notifier, AuditEmitter audit) {
        this.members = members;
        this.apps = apps;
        this.holders = holders;
        this.access = access;
        this.delegated = delegated;
        this.notifier = notifier;
        this.audit = audit;
    }

    // ---- flow B: what the selected people hold ---------------------------------------------

    public Map<String, Object> clientsForUsers(InstitutionContext ctx, CallerPrincipal caller, List<String> userIds) {
        InstitutionContextService.requireCapability(ctx, Capability.VIEW_MEMBERS);
        InstitutionContextService.requireCapability(ctx, Capability.VIEW_CLIENTS);
        KeycloakExec exec = delegated.forCaller(caller);
        Map<String, UserRepresentation> memberMap = members.membersById(ctx, exec);
        List<String> wanted = InstitutionMembers.assertAllMembers(ctx, userIds, memberMap);
        Lazy<List<String>> own = new Lazy<>(() -> members.memberGroupIds(ctx, exec));

        List<Map<String, Object>> clients = new ArrayList<>();
        Map<String, List<String>> byUser = new LinkedHashMap<>();
        wanted.forEach(u -> byUser.put(u, new ArrayList<>()));
        int pairs = 0;
        for (App app : apps.apps(ctx)) {
            Map<String, Holder> held = holders.holders(exec, app.clientId(), memberMap, app.crossCutting(), own);
            List<String> heldBy = wanted.stream().filter(u -> AccessMatrix.hasAccess(held.get(u))).toList();
            if (heldBy.isEmpty()) {
                continue;
            }
            heldBy.forEach(u -> byUser.get(u).add(app.clientId()));
            pairs += heldBy.size();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("client_id", app.clientId());
            row.put("name", app.name());
            row.put("cross_cutting", app.crossCutting());
            row.put("revocable", app.manageable());
            row.put("user_ids", heldBy);
            row.put("holders", heldBy.size());
            clients.add(row);
        }
        Map<String, String> usernames = new LinkedHashMap<>();
        wanted.forEach(u -> usernames.put(u, usernameOf(memberMap, u)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("institution", ctx.institution());
        out.put("clients", clients);
        out.put("by_user", byUser);
        out.put("usernames", usernames);
        out.put("total_pairs", pairs);
        return out;
    }

    // ---- the revocation itself -------------------------------------------------------------

    public Map<String, Object> revoke(InstitutionContext ctx, CallerPrincipal caller, RevokeCommand cmd,
                                      String requestIp) {
        InstitutionContextService.requireCapability(ctx, Capability.VIEW_MEMBERS);
        InstitutionContextService.requireCapability(ctx, Capability.MANAGE_CLIENTS);
        KeycloakExec exec = delegated.forCaller(caller);
        Map<String, UserRepresentation> memberMap = members.membersById(ctx, exec);
        List<String> wanted = InstitutionMembers.assertAllMembers(ctx, cmd.userIds(), memberMap);

        Map<String, App> appsById = new LinkedHashMap<>();
        apps.apps(ctx).forEach(a -> appsById.put(a.clientId(), a));
        Set<String> manageable = new TreeSet<>();
        appsById.values().stream().filter(App::manageable).forEach(a -> manageable.add(a.clientId()));

        List<String> selected;
        List<String> requestedClients = cmd.clientIds() == null ? List.of() : cmd.clientIds();
        if (cmd.allClients()) {
            if (!requestedClients.isEmpty()) {
                throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "Send either all_clients or an explicit "
                        + "client_ids list, not both — one of them is not what you meant.");
            }
            selected = List.copyOf(manageable);
        } else {
            selected = List.copyOf(new LinkedHashSet<>(requestedClients));
            if (selected.isEmpty()) {
                throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "No clients were selected. Name the clients "
                        + "to revoke, or set all_clients with confirm_all_clients.");
            }
            List<String> outside = selected.stream().filter(c -> !manageable.contains(c)).toList();
            if (!outside.isEmpty()) {
                throw ApiException.forbidden("client_out_of_scope", "Client(s) " + Py.list(outside)
                        + " are not yours to change access on. Institution '" + ctx.institution() + "' may manage: "
                        + (manageable.isEmpty() ? "none" : Py.list(manageable)) + ". Nothing was changed.");
            }
        }

        Map<String, Row> results = new LinkedHashMap<>();
        wanted.forEach(u -> results.put(u, new Row(u, usernameOf(memberMap, u))));
        boolean dryRun = cmd.preview() || (cmd.allClients() && !cmd.confirmAllClients());
        String batchId = AccessMatrix.newBatchId();
        Lazy<List<String>> own = new Lazy<>(() -> members.memberGroupIds(ctx, exec));

        for (String clientId : selected) {
            boolean shared = appsById.get(clientId).crossCutting();
            Map<String, Holder> held = holders.holders(exec, clientId, memberMap, shared, own);
            List<String> toRevoke = new ArrayList<>();
            for (String uid : wanted) {
                Holder h = held.get(uid);
                if (h == null) {
                    results.get(uid).skip(clientId, REASON_NOT_ASSIGNED,
                            "This user does not have access to that application, so there was nothing to remove.");
                } else if (h.denied()) {
                    results.get(uid).skip(clientId, REASON_ALREADY_REVOKED,
                            "Access to that application was already revoked for this user.");
                } else {
                    toRevoke.add(uid);
                }
            }
            if (toRevoke.isEmpty()) {
                continue;
            }
            if (dryRun) {
                toRevoke.forEach(uid -> results.get(uid).revoked.add(clientId));
                continue;
            }
            RevokeResult outcome;
            try {
                List<RevokeTarget> targets = toRevoke.stream()
                        .map(uid -> new RevokeTarget(uid, held.get(uid).groupIds())).toList();
                outcome = access.revokeUsers(holders.policiesExec(exec, shared), clientId, targets);
            } catch (ApiException | WebApplicationException | ProcessingException e) {
                // One client failing must not abandon the rest: the instruction was per client.
                String why = e instanceof WebApplicationException w
                        ? KeycloakErrors.message(w, "Keycloak refused the write") : e.getMessage();
                log.error("access revocation: {} failed for {} user(s) in {}: {}", clientId, toRevoke.size(),
                        ctx.institution(), why);
                toRevoke.forEach(uid -> results.get(uid).skip(clientId, REASON_FAILED, why));
                continue;
            }
            Set<String> unassigned = Set.copyOf(outcome.unassigned());
            Set<String> denied = Set.copyOf(outcome.denied());
            for (String uid : toRevoke) {
                if (unassigned.contains(uid) || denied.contains(uid)) {
                    results.get(uid).revoked.add(clientId);
                    audit.emit(AuditEvent.of(AuditEventType.ADMIN_CLIENT_UPDATED).actor(ctx.actor()).clientId(clientId)
                            .requestIp(requestIp).institution(ctx.institution()).targetClient(clientId)
                            .targetUser(uid)
                            .put("action", "revoke_user_access")
                            .put("mode", Action.REVOKE.code())
                            .put("channel", Channel.UI.code())
                            .put("batch_id", batchId)
                            .put("timestamp", Instant.now().toString())
                            .put("target_username", results.get(uid).username)
                            .put("unassigned", unassigned.contains(uid))
                            .put("denied", denied.contains(uid)).build());
                } else {
                    results.get(uid).skip(clientId, REASON_ALREADY_REVOKED,
                            "Nothing to remove: the assignment was gone by the time the change was applied.");
                }
            }
        }

        Map<String, Integer> notifications = new LinkedHashMap<>();
        if (!dryRun) {
            Map<String, String> labels = new LinkedHashMap<>();
            appsById.values().forEach(a -> labels.put(a.clientId(), Mailer.appLabel(a.clientId(), a.name())));
            AccessChangeNotifier.Batch batch = notifier.begin(AccessChangeNotifier.Kind.REVOKE, ctx.institution());
            for (Row r : results.values()) {
                UserRepresentation u = memberMap.get(r.userId);
                if (u != null) {
                    r.revoked.forEach(c -> batch.add(AccessChangeNotifier.Recipient.of(u), c, labels.get(c)));
                }
            }
            Map<String, String> notified = batch.flush();
            notified.forEach((uid, status) -> {
                Row r = results.get(uid);
                if (r != null) {
                    r.notified = status;
                }
            });
            notifications = AccessChangeNotifier.summarize(notified);
        }

        List<Map<String, Object>> rows = results.values().stream().map(Row::describe).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("institution", ctx.institution());
        out.put("preview", dryRun);
        out.put("notifications", notifications);
        out.put("all_clients", cmd.allClients());
        out.put("clients_considered", selected);
        out.put("users", rows);
        out.put("total_revocations", results.values().stream().mapToInt(r -> r.revoked.size()).sum());
        out.put("total_skipped", results.values().stream().mapToInt(r -> r.skipped.size()).sum());
        out.put("users_affected", (int) results.values().stream().filter(r -> !r.revoked.isEmpty()).count());
        return out;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static String usernameOf(Map<String, UserRepresentation> memberMap, String uid) {
        UserRepresentation u = memberMap.get(uid);
        return u == null || u.getUsername() == null ? uid : u.getUsername();
    }

    private static final class Row {
        final String userId;
        final String username;
        final List<String> revoked = new ArrayList<>();
        final List<Map<String, Object>> skipped = new ArrayList<>();
        String notified;

        Row(String userId, String username) {
            this.userId = userId;
            this.username = username;
        }

        void skip(String clientId, String reason, String message) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("client_id", clientId);
            s.put("reason", reason);
            s.put("message", message);
            skipped.add(s);
        }

        Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user_id", userId);
            m.put("username", username);
            m.put("revoked", revoked);
            m.put("skipped", skipped);
            if (notified != null) {
                m.put("notified", notified);
            }
            return m;
        }
    }

    /** A memoising supplier (the member group walk runs only when a client has a group assigned). */
    static final class Lazy<T> implements java.util.function.Supplier<T> {
        private final java.util.function.Supplier<T> source;
        private T value;
        private boolean done;

        Lazy(java.util.function.Supplier<T> source) {
            this.source = source;
        }

        @Override
        public T get() {
            if (!done) {
                value = source.get();
                done = true;
            }
            return value;
        }
    }
}
