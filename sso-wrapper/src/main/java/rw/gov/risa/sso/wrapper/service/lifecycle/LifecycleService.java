package rw.gov.risa.sso.wrapper.service.lifecycle;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.audit.AuditScope;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisOutcome;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisReconcilePolicy;
import rw.gov.risa.sso.wrapper.policy.ippisperson.PersonView;
import rw.gov.risa.sso.wrapper.policy.ippisperson.ReconcileOutcome;
import rw.gov.risa.sso.wrapper.policy.lifecycle.GroupRef;
import rw.gov.risa.sso.wrapper.policy.lifecycle.OffboardingInput;
import rw.gov.risa.sso.wrapper.policy.lifecycle.OffboardingPlan;
import rw.gov.risa.sso.wrapper.policy.lifecycle.OffboardingPlanner;
import rw.gov.risa.sso.wrapper.policy.lifecycle.ReassignmentPlan;
import rw.gov.risa.sso.wrapper.policy.lifecycle.RehirePolicy;

/**
 * Employee lifecycle (ports {@code core/lifecycle.py} + {@code services/ippis_leaver.py}, extended
 * by the spec): atomic offboarding with rollback, access reassignment, the leaver decision of the
 * IPPIS feeds, and the explicit, audited re-hire rule.
 *
 * <p>Offboarding, in {@link rw.gov.risa.sso.wrapper.policy.lifecycle.OffboardingStep} order: end
 * sessions → disable (+ offboarding markers) → remove every group → strip {@code inst:<slug>:*}
 * realm roles → remove {@code sso-access-users} named grants. A failure rolls back what can be
 * (re-enable, re-add groups and roles; sessions cannot be restored) and is audited as a failure.
 * The actor ({@code triggeredBy}) is the authenticated principal or the feed's fixed name — never
 * a request body.
 */
@Service
public class LifecycleService {

    private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);

    private final KeycloakUserGateway gateway;
    private final LeaverAccessStripper stripper;
    private final AuditEmitter audit;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public LifecycleService(KeycloakUserGateway gateway, LeaverAccessStripper stripper, AuditEmitter audit) {
        this(gateway, stripper, audit, Clock.systemUTC());
    }

    LifecycleService(KeycloakUserGateway gateway, LeaverAccessStripper stripper, AuditEmitter audit, Clock clock) {
        this.gateway = gateway;
        this.stripper = stripper;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- POST /offboarding/terminate ----------------------------------------------------------

    public OffboardingResult terminate(String userId, String reason, String triggeredBy) {
        try (UserOps ops = gateway.open()) {
            return terminate(ops, userId, reason, triggeredBy);
        }
    }

    /**
     * @throws ApiException 404 {@code not_found} when the user does not exist; 409
     *                      {@code lifecycle_failed} after a failure was rolled back
     */
    public OffboardingResult terminate(UserOps ops, String userId, String reason, String triggeredBy) {
        UserRepresentation user = ops.getUser(userId)
                .orElseThrow(() -> ApiException.notFound("User '" + userId + "' not found in realm"));

        List<GroupRef> groups = ops.groups(userId).stream().map(g -> new GroupRef(g.getId(), g.getName())).toList();
        List<RoleRepresentation> directRoles = ops.realmRoles(userId);
        int sessions = ops.sessionCount(userId);
        OffboardingPlan plan = OffboardingPlanner.plan(
                new OffboardingInput(userId, user.getUsername(), !Boolean.FALSE.equals(user.isEnabled()), sessions,
                        groups, directRoles.stream().map(RoleRepresentation::getName).toList(), List.of(),
                        user.getAttributes()),
                reason, triggeredBy, clock.instant());

        boolean disabled = false;
        List<GroupRef> groupsRemoved = new ArrayList<>();
        List<RoleRepresentation> rolesStripped = new ArrayList<>();
        try {
            ops.logout(userId);
            ops.setEnabled(userId, false, plan.markerAttributes());
            disabled = true;
            for (GroupRef g : plan.groupsToRemove()) {
                ops.removeFromGroup(userId, g.id());
                groupsRemoved.add(g);
            }
            List<RoleRepresentation> toStrip = directRoles.stream()
                    .filter(r -> plan.rolesToStrip().contains(r.getName())).toList();
            ops.removeRealmRoles(userId, toStrip);
            rolesStripped.addAll(toStrip);
            List<String> grants = stripper.removeNamedGrantsEverywhere(userId);

            Map<String, Object> extra = new LinkedHashMap<>(plan.auditExtra());
            extra.put("client_grants_removed", grants);
            extra.put("triggered_by", triggeredBy);
            audit.emit(AuditEvent.of(AuditEventType.LIFECYCLE_OFFBOARD).success().actor(triggeredBy).putAll(extra).build());
            log.info("lifecycle.terminate: offboarded {} (sessions={}, groups={}, roles={}, grants={})", userId,
                    sessions, groupsRemoved.size(), rolesStripped.size(), grants.size());
            return new OffboardingResult(true, userId, sessions,
                    groupsRemoved.stream().map(g -> new OffboardingResult.GroupSnapshot(g.id(), g.name())).toList(),
                    rolesStripped.stream().map(RoleRepresentation::getName).toList(), grants, true, now(), false,
                    null);
        } catch (RuntimeException e) {
            log.error("lifecycle.terminate: failed for {} ({}) — rolling back", userId, e.toString());
            try {
                if (disabled) {
                    ops.setEnabled(userId, true, Map.of());
                }
                for (GroupRef g : groupsRemoved) {
                    ops.addToGroup(userId, g.id());
                }
                ops.addRealmRoles(userId, rolesStripped);
            } catch (RuntimeException rb) {
                log.error("lifecycle.terminate rollback: error for {}: {}", userId, rb.toString());
            }
            audit.emit(AuditEvent.of(AuditEventType.LIFECYCLE_OFFBOARD).failure().actor(triggeredBy)
                    .putAll(plan.failureAuditExtra(String.valueOf(e.getMessage()))).build());
            throw new ApiException(HttpStatus.CONFLICT, LifecycleErrors.LIFECYCLE_FAILED,
                    "Offboarding failed and was rolled back: " + e.getMessage());
        }
    }

    // ---- POST /access/reassign -----------------------------------------------------------------

    public ReassignmentResult reassign(String userId, List<String> removeGroups, List<String> addGroups,
                                       boolean forceSessionRefresh, String reason, String triggeredBy) {
        try (UserOps ops = gateway.open()) {
            Optional<UserRepresentation> found = ops.getUser(userId);
            String refusal = ReassignmentPlan.refusal(found.isPresent(), found.map(UserRepresentation::isEnabled).orElse(null));
            if (ReassignmentPlan.ERROR_USER_NOT_FOUND.equals(refusal)) {
                throw ApiException.notFound("User '" + userId + "' not found in realm");
            }
            if (ReassignmentPlan.ERROR_ACCOUNT_DISABLED.equals(refusal)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, LifecycleErrors.ACCOUNT_DISABLED,
                        "User '" + userId + "' is disabled — use /offboarding/terminate for inactive accounts");
            }
            UserRepresentation user = found.orElseThrow();
            Map<String, String> ids = new LinkedHashMap<>();
            for (String name : concat(removeGroups, addGroups)) {
                ops.findGroupByName(name).map(GroupRepresentation::getId).ifPresent(id -> ids.put(name, id));
            }
            ReassignmentPlan plan = ReassignmentPlan.plan(removeGroups, addGroups, ids);
            String institution = AuditScope.institutionOfUser(user.getAttributes());

            List<String> removed = new ArrayList<>();
            List<String> added = new ArrayList<>();
            boolean refreshed = false;
            try {
                for (Map.Entry<String, String> g : plan.remove().entrySet()) {
                    ops.removeFromGroup(userId, g.getValue());
                    removed.add(g.getKey());
                }
                for (Map.Entry<String, String> g : plan.add().entrySet()) {
                    ops.addToGroup(userId, g.getValue());
                    added.add(g.getKey());
                }
                if (forceSessionRefresh) {
                    ops.logout(userId);
                    refreshed = true;
                }
                audit.emit(AuditEvent.of(AuditEventType.LIFECYCLE_REASSIGN).success().actor(triggeredBy)
                        .put("user_id", userId).targetUser(userId).put("target_username", user.getUsername())
                        .institution(institution).put("reason", reason).put("groups_removed", removed)
                        .put("groups_added", added).put("groups_not_found", plan.groupsNotFound())
                        .put("session_refreshed", refreshed).build());
                return new ReassignmentResult(true, userId, removed, added, plan.groupsNotFound(), refreshed, now(),
                        false, null);
            } catch (RuntimeException e) {
                log.error("lifecycle.reassign: failed for {} ({}) — rolling back", userId, e.toString());
                try {
                    for (String g : added) {
                        ops.removeFromGroup(userId, plan.add().get(g));
                    }
                    for (String g : removed) {
                        ops.addToGroup(userId, plan.remove().get(g));
                    }
                } catch (RuntimeException rb) {
                    log.error("lifecycle.reassign rollback: error for {}: {}", userId, rb.toString());
                }
                String err = String.valueOf(e.getMessage());
                audit.emit(AuditEvent.of(AuditEventType.LIFECYCLE_REASSIGN).failure().actor(triggeredBy)
                        .put("user_id", userId).targetUser(userId).put("target_username", user.getUsername())
                        .institution(institution).put("reason", reason)
                        .put("error", err.length() > 300 ? err.substring(0, 300) : err).put("rolled_back", true).build());
                throw new ApiException(HttpStatus.CONFLICT, LifecycleErrors.LIFECYCLE_FAILED,
                        "Reassignment failed and was rolled back: " + e.getMessage());
            }
        }
    }

    // ---- IPPIS leaver decision (reconcile, webhook) --------------------------------------------

    /**
     * {@code apply_leaver}: offboard iff the flattened record says the person has left (no open
     * post ANYWHERE — resigning one of two posts is a move). Never throws.
     */
    public ReconcileOutcome applyLeaver(UserOps ops, UserRepresentation user, PersonView view, String triggeredBy,
                                        boolean dryRun) {
        String userId = user.getId();
        switch (IppisReconcilePolicy.leaverDecision(user.isEnabled(), view)) {
            case ACTIVE:
                return ReconcileOutcome.of(userId, IppisOutcome.ACTIVE, view.status());
            case ALREADY_DISABLED:
                return ReconcileOutcome.of(userId, IppisOutcome.ALREADY_DISABLED, null);
            default:
                break;
        }
        if (dryRun) {
            return ReconcileOutcome.of(userId, IppisOutcome.DISABLED, "dry-run: would offboard " + view.employeeNumber());
        }
        try {
            terminate(ops, userId, IppisReconcilePolicy.leaverReason(view), triggeredBy);
            log.info("ippis leaver: disabled {} (no open post anywhere)", userId);
            return ReconcileOutcome.of(userId, IppisOutcome.DISABLED, view.employeeNumber());
        } catch (RuntimeException e) {
            return ReconcileOutcome.of(userId, IppisOutcome.ERROR, e.getMessage());
        }
    }

    /**
     * The explicit re-hire rule ({@link RehirePolicy}): a feed-offboarded account whose person
     * IPPIS again shows in an open post is re-enabled (account only — roles, groups and grants are
     * granted afresh), its markers cleared, audited {@code lifecycle.rehire}. An account an admin
     * disabled by hand is never re-enabled by a feed.
     */
    public RehirePolicy.Decision rehireIfEligible(UserOps ops, UserRepresentation user, PersonView view,
                                                  String triggeredBy, boolean dryRun) {
        RehirePolicy.Decision d = RehirePolicy.decide(user.isEnabled(), view, user.getAttributes());
        if (d == RehirePolicy.Decision.MANUAL_DISABLE_KEPT) {
            log.info("ippis rehire: {} shows an open post but was disabled by hand — kept disabled", user.getId());
        }
        if (d != RehirePolicy.Decision.REENABLE || dryRun) {
            return d;
        }
        Map<String, Object> extra = RehirePolicy.auditExtra(user.getId(), user.getUsername(), view, user.getAttributes());
        ops.setEnabled(user.getId(), true, RehirePolicy.clearMarkers());
        user.setEnabled(true);
        audit.emit(AuditEvent.of(AuditEventType.LIFECYCLE_REHIRE).success().actor(triggeredBy)
                .institution(AuditScope.institutionOfUser(user.getAttributes())).putAll(extra).build());
        log.info("ippis rehire: re-enabled {} ({})", user.getId(), view.employeeNumber());
        return d;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
