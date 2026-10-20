package rw.gov.risa.sso.wrapper.service.ippis;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.identity.MatchState;
import rw.gov.risa.sso.wrapper.policy.ippisperson.AttributeSyncPlan;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisAttributeProjection;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisOutcome;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPerson;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPersonViews;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisReconcilePolicy;
import rw.gov.risa.sso.wrapper.policy.ippisperson.PersonView;
import rw.gov.risa.sso.wrapper.policy.ippisperson.ReconcileOutcome;
import rw.gov.risa.sso.wrapper.policy.ippisperson.ReconcileSummary;
import rw.gov.risa.sso.wrapper.policy.lifecycle.RehirePolicy;
import rw.gov.risa.sso.wrapper.policy.registry.InstitutionRegistry;
import rw.gov.risa.sso.wrapper.service.lifecycle.KeycloakUserGateway;
import rw.gov.risa.sso.wrapper.service.lifecycle.LifecycleService;
import rw.gov.risa.sso.wrapper.service.lifecycle.UserOps;

/**
 * The unified IPPIS reconcile ({@code ippis_reconcile.py} + {@code ippis_sync.py}): one IPPIS fetch
 * per person that both offboards the leaver and, for everyone else, sources the Keycloak attributes
 * {@code institution} (registry slug via ippis_codes, never raw), {@code position},
 * {@code national_id}, {@code phone}, {@code ippis_status} — keyed by {@code employee_number}.
 * It NEVER writes the {@code email} property and NEVER creates an account.
 *
 * <ul>
 *   <li>{@link #runDelta} — IPPIS-driven: people changed since the Redis watermark, resolved to their
 *       Keycloak user(s). Fills the mirror's joiner queue ({@code no_user}).</li>
 *   <li>{@link #runFull} — Keycloak-driven safety net over every realm user; re-anchors the
 *       watermark to now.</li>
 * </ul>
 * Bounded-concurrent (IPPIS_SYNC_CONCURRENCY), every user error-isolated, one
 * {@code ippis.sync.attributes} audit per changed user and ONE {@code ippis.sync.unmapped_institution}
 * per run that saw any unmapped code. The mirror (when configured) records every observation and
 * never changes an outcome.
 */
@Service
public class IppisReconcileService {

    private static final Logger log = LoggerFactory.getLogger(IppisReconcileService.class);

    public static final String TRIGGERED_BY = IppisReconcilePolicy.RECONCILE_TRIGGERED_BY;

    private final IppisClient ippis;
    private final KeycloakUserGateway gateway;
    private final LifecycleService lifecycle;
    private final IppisRegistryProvider registry;
    private final IdentityMirrorWriter mirror;
    private final WatermarkStore watermark;
    private final AuditEmitter audit;
    private final IppisProperties props;
    private final TransientRetry writeRetry;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public IppisReconcileService(IppisClient ippis, KeycloakUserGateway gateway, LifecycleService lifecycle,
                                 IppisRegistryProvider registry, IdentityMirrorWriter mirror, WatermarkStore watermark,
                                 AuditEmitter audit, IppisProperties props) {
        this(ippis, gateway, lifecycle, registry, mirror, watermark, audit, props, Clock.systemUTC());
    }

    IppisReconcileService(IppisClient ippis, KeycloakUserGateway gateway, LifecycleService lifecycle,
                          IppisRegistryProvider registry, IdentityMirrorWriter mirror, WatermarkStore watermark,
                          AuditEmitter audit, IppisProperties props, Clock clock) {
        this.ippis = ippis;
        this.gateway = gateway;
        this.lifecycle = lifecycle;
        this.registry = registry;
        this.mirror = mirror;
        this.watermark = watermark;
        this.audit = audit;
        this.props = props;
        this.writeRetry = new TransientRetry("keycloak-write", props.effectiveMaxAttempts(), props.retryBackoff());
        this.clock = clock;
    }

    /** Options for one run. {@code useMirror=false} = {@code --no-mirror}. */
    public record RunOptions(boolean dryRun, boolean useMirror, Integer maxUsers, String triggeredBy) {
        public static RunOptions defaults() {
            return new RunOptions(false, true, null, TRIGGERED_BY);
        }
    }

    // ---- delta ---------------------------------------------------------------------------------

    public RunReport runDelta(RunOptions opts) {
        Instant since = watermark.read().orElse(null);
        List<IppisPerson> changed = ippis.listPeopleChangedSince(since);
        log.info("ippis reconcile (delta): {} changed record(s) since {}", changed.size(),
                since == null ? "(beginning)" : since);
        Optional<IdentityMirrorWriter.Recorder> rec = recorder(opts);
        List<String> rehired = Collections.synchronizedList(new ArrayList<>());
        List<ReconcileOutcome> outcomes = new ArrayList<>();
        try (UserOps ops = gateway.open()) {
            for (List<ReconcileOutcome> group : parallel(changed,
                    person -> () -> deltaOne(ops, person, opts, rec, rehired))) {
                outcomes.addAll(group);
            }
        }
        Instant next = IppisReconcilePolicy.newWatermark(changed.stream().map(IppisPerson::updatedAt).toList(), since);
        if (!opts.dryRun() && next != null) {
            watermark.write(next);
        }
        return finish("delta", opts, since, next, outcomes, rec, rehired);
    }

    private List<ReconcileOutcome> deltaOne(UserOps ops, IppisPerson person, RunOptions opts,
                                            Optional<IdentityMirrorWriter.Recorder> rec, List<String> rehired) {
        PersonView view = IppisPersonViews.viewOf(person);
        String attr = props.employeeNumberAttribute();
        List<UserRepresentation> matches;
        try {
            matches = ops.findByAttribute(attr, view.employeeNumber());
        } catch (RuntimeException e) {
            log.warn("ippis reconcile (delta): user lookup failed for {}: {}", view.employeeNumber(), e.toString());
            // match_state null: the lookup failed, so nothing was learned about matching.
            return List.of(record(rec, person, view, ReconcileOutcome.of(null, IppisOutcome.ERROR, e.getMessage()),
                    null, null, opts));
        }
        if (matches.isEmpty()) {
            return List.of(record(rec, person, view, IppisReconcilePolicy.noUser(attr, view.employeeNumber()), null,
                    MatchState.UNMATCHED, opts));
        }
        List<ReconcileOutcome> outcomes = new ArrayList<>();
        for (UserRepresentation u : matches) {
            outcomes.add(dispatch(ops, u, view, opts.triggeredBy(), opts.dryRun(), rehired));
        }
        MatchState state = switch (IppisReconcilePolicy.matchState(matches.size())) {
            case AMBIGUOUS -> MatchState.AMBIGUOUS;
            case MATCHED -> MatchState.MATCHED;
            case UNMATCHED -> MatchState.UNMATCHED;
        };
        if (state == MatchState.AMBIGUOUS) {
            log.warn("ippis reconcile (delta): {} Keycloak users share {}={} — all were written; recorded as ambiguous",
                    matches.size(), attr, view.employeeNumber());
        }
        // One mirror row per employee number, anchored to the first match.
        ReconcileOutcome first = record(rec, person, view, outcomes.get(0), matches.get(0).getId(), state, opts);
        outcomes.set(0, first);
        return outcomes;
    }

    // ---- full ----------------------------------------------------------------------------------

    public RunReport runFull(RunOptions opts) {
        Optional<IdentityMirrorWriter.Recorder> rec = recorder(opts);
        List<String> rehired = Collections.synchronizedList(new ArrayList<>());
        List<ReconcileOutcome> outcomes = new ArrayList<>();
        Instant startedAt = clock.instant();
        try (UserOps ops = gateway.open()) {
            List<UserRepresentation> users = allUsers(ops, opts.maxUsers());
            log.info("ippis reconcile (full): {} user(s)", users.size());
            outcomes.addAll(parallel(users, u -> () -> reconcileUser(ops, u, opts, rec, rehired)));
        }
        if (!opts.dryRun()) {
            // A full pass covers everything up to its start — advance the delta watermark.
            watermark.write(startedAt);
        }
        return finish("full", opts, null, startedAt, outcomes, rec, rehired);
    }

    // ---- leavers only ---------------------------------------------------------------------------

    /**
     * {@code process_ippis_leavers}: a pass over realm users that ONLY offboards leavers (no
     * attribute writes, no mirror). Outcomes: skipped / not_found / active / already_disabled /
     * disabled / error.
     */
    public RunReport runLeavers(RunOptions opts) {
        String attr = props.employeeNumberAttribute();
        List<ReconcileOutcome> outcomes = new ArrayList<>();
        try (UserOps ops = gateway.open()) {
            List<UserRepresentation> users = allUsers(ops, opts.maxUsers());
            outcomes.addAll(parallel(users, u -> () -> {
                Optional<String> number = IppisReconcilePolicy.employeeNumberOf(u.getAttributes(), attr);
                if (number.isEmpty()) {
                    return IppisReconcilePolicy.skipped(u.getId(), attr);
                }
                try {
                    Optional<IppisPerson> person = ippis.getPerson(number.get());
                    if (person.isEmpty()) {
                        return IppisReconcilePolicy.notFound(u.getId(), number.get());
                    }
                    return lifecycle.applyLeaver(ops, u, IppisPersonViews.viewOf(person.get()),
                            IppisReconcilePolicy.LEAVER_TRIGGERED_BY, opts.dryRun());
                } catch (RuntimeException e) {
                    return ReconcileOutcome.of(u.getId(), IppisOutcome.ERROR, e.getMessage());
                }
            }));
        }
        ReconcileSummary summary = ReconcileSummary.of(outcomes, null);
        RunReport report = new RunReport("leavers", opts.dryRun(), null, null, summary, List.of(), null);
        log.info("ippis leavers: {}", report.toMap());
        return report;
    }

    /** {@code reconcile_user}: fetch one user's IPPIS person once, offboard-or-enrich, record. */
    ReconcileOutcome reconcileUser(UserOps ops, UserRepresentation user, RunOptions opts,
                                   Optional<IdentityMirrorWriter.Recorder> rec, List<String> rehired) {
        String attr = props.employeeNumberAttribute();
        Optional<String> number = IppisReconcilePolicy.employeeNumberOf(user.getAttributes(), attr);
        if (number.isEmpty()) {
            return IppisReconcilePolicy.skipped(user.getId(), attr);
        }
        Optional<IppisPerson> person;
        try {
            person = ippis.getPerson(number.get());
        } catch (RuntimeException e) {
            log.warn("ippis reconcile: lookup failed for {}: {}", number.get(), e.toString());
            return ReconcileOutcome.of(user.getId(), IppisOutcome.ERROR, e.getMessage());
        }
        if (person.isEmpty()) {
            return IppisReconcilePolicy.notFound(user.getId(), number.get());
        }
        PersonView view = IppisPersonViews.viewOf(person.get());
        ReconcileOutcome outcome = dispatch(ops, user, view, opts.triggeredBy(), opts.dryRun(), rehired);
        return record(rec, person.get(), view, outcome, user.getId(), MatchState.MATCHED, opts);
    }

    // ---- per-user decision (shared with the webhook) ---------------------------------------------

    /**
     * Leavers (no open post anywhere) are offboarded; everyone else is first checked against the
     * re-hire rule, then enriched. Never throws.
     */
    public ReconcileOutcome dispatch(UserOps ops, UserRepresentation user, PersonView view, String triggeredBy,
                                     boolean dryRun, List<String> rehired) {
        try {
            if (IppisReconcilePolicy.dispatch(view) == IppisReconcilePolicy.Dispatch.OFFBOARD_IF_ENABLED) {
                return lifecycle.applyLeaver(ops, user, view, triggeredBy, dryRun);
            }
            RehirePolicy.Decision d = lifecycle.rehireIfEligible(ops, user, view, triggeredBy, dryRun);
            if (d == RehirePolicy.Decision.REENABLE && rehired != null) {
                rehired.add(user.getId());
            }
            return applyAttributes(ops, user, view, triggeredBy, dryRun);
        } catch (RuntimeException e) {
            log.warn("ippis reconcile: {} failed: {}", user.getId(), e.toString());
            return ReconcileOutcome.of(user.getId(), IppisOutcome.ERROR, e.getMessage());
        }
    }

    /** {@code apply_attributes}: write the mapped delta (only if something changed); audit it. */
    public ReconcileOutcome applyAttributes(UserOps ops, UserRepresentation user, PersonView view, String triggeredBy,
                                            boolean dryRun) {
        InstitutionRegistry reg = registry.registry();
        AttributeSyncPlan plan = IppisAttributeProjection.plan(user.getAttributes(), view, reg, props.clearMissing());
        String userId = user.getId();
        if (plan.isUnchanged()) {
            return flags(ReconcileOutcome.of(userId, IppisOutcome.UNCHANGED, null), plan);
        }
        if (dryRun) {
            return flags(ReconcileOutcome.of(userId, IppisOutcome.UPDATED, "dry-run: " + plan.changedKeys()), plan);
        }
        boolean ok;
        try {
            ok = writeRetry.call(() -> ops.updateAttributes(userId, plan.write()));
        } catch (RuntimeException e) {
            log.warn("ippis sync: write failed for {}: {}", userId, e.toString());
            return flags(ReconcileOutcome.of(userId, IppisOutcome.ERROR, e.getMessage()), plan);
        }
        if (!ok) {
            return flags(ReconcileOutcome.of(userId, IppisOutcome.ERROR, "user not found in Keycloak"), plan);
        }
        audit.emit(AuditEvent.of(AuditEventType.SYNC_ATTRIBUTES).success().actor(triggeredBy)
                .putAll(plan.auditExtra(userId)).build());
        return flags(ReconcileOutcome.of(userId, IppisOutcome.UPDATED, String.join(",", plan.changedKeys())), plan);
    }

    private static ReconcileOutcome flags(ReconcileOutcome o, AttributeSyncPlan plan) {
        return new ReconcileOutcome(o.userId(), o.status(), o.detail(), plan.unmappedInstitution(),
                plan.ambiguousInstitution(), false, null);
    }

    // ---- mirror + summary ------------------------------------------------------------------------

    private Optional<IdentityMirrorWriter.Recorder> recorder(RunOptions opts) {
        if (!opts.useMirror() || opts.dryRun()) {
            return Optional.empty();
        }
        Optional<IdentityMirrorWriter.Recorder> r = mirror.recorder();
        if (r.isEmpty()) {
            log.info("ippis mirror: IDENTITY_DATABASE_URL is not set — syncing straight from IPPIS with no local "
                    + "mirror (docs/runbooks/create-sso-identity-db.md)");
        }
        return r;
    }

    /**
     * Stamps the unmapped/ambiguous flags from the view (so an employee with no account is still
     * reported) and writes the observation to the mirror. Never changes the status.
     */
    private ReconcileOutcome record(Optional<IdentityMirrorWriter.Recorder> rec, IppisPerson person, PersonView view,
                                    ReconcileOutcome outcome, String keycloakUserId, MatchState state, RunOptions opts) {
        ReconcileOutcome stamped = outcome.withFlagsFrom(view,
                IppisAttributeProjection.resolveInstitutionCode(view.institutionCode(), registry.registry()));
        if (rec.isEmpty()) {
            return stamped;
        }
        String error = rec.get().record(person, stamped, keycloakUserId, state);
        return stamped.withMirror(error == null, error);
    }

    private RunReport finish(String mode, RunOptions opts, Instant since, Instant next, List<ReconcileOutcome> outcomes,
                             Optional<IdentityMirrorWriter.Recorder> rec, List<String> rehired) {
        ReconcileSummary summary = ReconcileSummary.of(outcomes, rec.map(r -> new ReconcileSummary.MirrorCounts(
                r.errors(), r.reassignments(), r.conflicts())).orElse(null));
        RunReport report = new RunReport(mode, opts.dryRun(), since, next, summary, rehired,
                rec.map(IdentityMirrorWriter.Recorder::summary).orElse(null));
        List<String> codes = report.unmappedCodes();
        if (!codes.isEmpty() && !opts.dryRun()) {
            audit.emit(AuditEvent.of(AuditEventType.SYNC_UNMAPPED_INSTITUTION).failure().actor(opts.triggeredBy())
                    .put("codes", codes).put("users_affected", summary.unmappedInstitution())
                    .put("registry", registry.registry().source()).build());
        }
        log.info("ippis reconcile ({}): {}", mode, report.toMap());
        return report;
    }

    private List<UserRepresentation> allUsers(UserOps ops, Integer maxUsers) {
        int page = Math.max(1, props.pageSize());
        List<UserRepresentation> out = new ArrayList<>();
        int first = 0;
        while (true) {
            List<UserRepresentation> batch = ops.listUsers(first, page);
            out.addAll(batch);
            if (batch.size() < page || (maxUsers != null && out.size() >= maxUsers)) {
                break;
            }
            first += page;
        }
        return maxUsers != null && out.size() > maxUsers ? out.subList(0, maxUsers) : out;
    }

    /** Order-preserving, bounded-concurrent map. */
    private <I, O> List<O> parallel(List<I> items, java.util.function.Function<I, Supplier<O>> work) {
        if (items.isEmpty()) {
            return List.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(props.effectiveConcurrency(), items.size()),
                Thread.ofPlatform().name("ippis-sync-", 0).daemon(true).factory());
        try {
            List<Future<O>> futures = new ArrayList<>();
            for (I item : items) {
                Supplier<O> s = work.apply(item);
                futures.add(pool.submit(s::get));
            }
            List<O> out = new ArrayList<>(futures.size());
            for (Future<O> f : futures) {
                out.add(f.get());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ippis reconcile interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("ippis reconcile worker failed", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }
}
