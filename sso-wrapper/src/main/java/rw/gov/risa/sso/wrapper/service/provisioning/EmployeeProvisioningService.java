package rw.gov.risa.sso.wrapper.service.provisioning;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccessMatrix;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Channel;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.Account;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.AccountResolver;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ClientTarget;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.EmployeeAccountPlanner;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.EmployeePlan;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.EmployeeRecord;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.HeldClients;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.Outcome;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.PlanAction;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ProvisionReport;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ProvisionRequestRules;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ReportRow;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.policy.setuplink.SetupLinkPolicy;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyProperties;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.mail.DeliveryResult;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService;

/**
 * Assigning applications from the employee list — the doing (spec §5.11; port of
 * {@code services/employee_provisioning.py}). The decisions are {@code policy.employeeaccounts}; this class
 * carries them out in the one order that leaves nothing dangling if it stops halfway:
 *
 * <pre>
 *   resolve every selected employee's account (whole batch, BEFORE planning)
 *     → read who already reaches each selected client (deny beats allow)
 *       → plan (pure; per (employee, client), never all-or-nothing)
 *         → create the users who need one (username = email, a temporary password nobody is told,
 *           UPDATE_PASSWORD required)
 *           → grant, per client and PER USER (admitUser), only what qualified and is not already held
 *             → put the NEW ones in the institution's group (membership — never how access is granted)
 *               → email: welcome + the wrapper's set-password link (new) / "access granted" (existing)
 *                 → record the account on the employee's mirror row
 * </pre>
 *
 * <ul>
 *   <li><b>The batch never fails as a batch.</b> Whole-request refusals: a client outside the caller's
 *       scope (403), none selected / too many employees (400), an unknown client (404).</li>
 *   <li><b>The temporary password is never disclosed</b> — not returned, not logged, not emailed.</li>
 *   <li><b>An existing account is never recreated or altered</b>: no group move, password, required action or
 *       email property; only the grants (and the mirror link, which records what was found).</li>
 *   <li>Every assignment is audited per (user, client) under one {@code batch_id}.</li>
 * </ul>
 *
 * Runs on the wrapper's service account ({@link ProvisioningKeycloak}); the institution boundary is held here
 * first (mirror query term + {@code assertClientInScope} per client).
 */
@Service
public class EmployeeProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeProvisioningService.class);

    public static final String PASSWORD_SET_BY_HOLDER = "set_by_holder";
    public static final String PASSWORD_SHARED_DEV = "shared_dev";
    public static final String EMAIL_NOT_NEEDED = "not_needed";
    public static final String EMAIL_SKIPPED_SHARED = "skipped_shared_password";

    /** One provisioning run. {@code batchId}/{@code channel}: set when a caller (the matrix) is itself one act. */
    public record Command(List<String> employeeNumbers, List<String> clientIds, boolean dryRun, String requestIp,
                          String batchId, Channel channel) {

        public static Command of(List<String> employeeNumbers, List<String> clientIds, boolean dryRun,
                                 String requestIp) {
            return new Command(employeeNumbers, clientIds, dryRun, requestIp, null, Channel.AUTO_PROVISIONING);
        }
    }

    private final ProvisioningKeycloak keycloak;
    private final EmployeeMirror mirror;
    private final ClientAccessService access;
    private final ClientScopeService scopes;
    private final JoinKeyService joinKeys;
    private final JoinKeyProperties joinKeyProps;
    private final SetupLinkService setupLinks;
    private final Mailer mailer;
    private final AuditEmitter audit;
    private final ProvisioningProperties props;
    private final SsoProperties sso;

    public EmployeeProvisioningService(ProvisioningKeycloak keycloak, EmployeeMirror mirror,
                                       ClientAccessService access, ClientScopeService scopes, JoinKeyService joinKeys,
                                       JoinKeyProperties joinKeyProps, SetupLinkService setupLinks, Mailer mailer,
                                       AuditEmitter audit, ProvisioningProperties props, SsoProperties sso) {
        this.keycloak = keycloak;
        this.mirror = mirror;
        this.access = access;
        this.scopes = scopes;
        this.joinKeys = joinKeys;
        this.joinKeyProps = joinKeyProps;
        this.setupLinks = setupLinks;
        this.mailer = mailer;
        this.audit = audit;
        this.props = props;
        this.sso = sso;
    }

    /** {@code EMPLOYEE_PROVISION_MAX_BATCH}. */
    public int maxBatch() {
        return props.maxBatch();
    }

    /**
     * The shared test password, or null when the real flow applies. Both conditions: the value is set AND
     * DEBUG is on — an env var alone must never give every new civil servant the same password.
     */
    public String devPassword() {
        String shared = props.devPassword().strip();
        return shared.isEmpty() || !sso.debug() ? null : shared;
    }

    // ---- the run ---------------------------------------------------------------------------

    public ProvisionReport run(InstitutionContext ctx, Command cmd) {
        // All three up front: one selection may hold both kinds of person, and discovering a missing
        // capability after creating six accounts would be the worst of both.
        ctx.assertCapability(Capability.MANAGE_MEMBERS);
        ctx.assertCapability(Capability.MANAGE_MEMBERSHIP);
        ctx.assertCapability(Capability.MANAGE_CLIENTS);
        String batchId = cmd.batchId() != null ? cmd.batchId() : AccessMatrix.newBatchId();
        Channel channel = cmd.channel() == null ? Channel.AUTO_PROVISIONING : cmd.channel();

        List<String> wantedClients;
        List<String> wantedNumbers;
        try {
            wantedClients = ProvisionRequestRules.clientIds(cmd.clientIds());
        } catch (ProvisionRequestRules.ProvisionRequestException e) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, e.getMessage());
        }
        List<ClientTarget> targets = loadTargets(ctx, wantedClients);
        try {
            wantedNumbers = ProvisionRequestRules.employeeNumbers(cmd.employeeNumbers(), props.maxBatch());
        } catch (ProvisionRequestRules.ProvisionRequestException e) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, e.getMessage());
        }
        ProvisionRequestRules.Found found = ProvisionRequestRules.order(wantedNumbers,
                mirror.records(ctx.institution(), wantedNumbers));

        // Account-awareness, before anything is decided — for the WHOLE batch.
        Directory dir = new Directory();
        AccountResolver.Resolution resolution = AccountResolver.resolve(found.records(), ctx.institution(), dir);
        resolution.refused().forEach((n, r) -> log.warn("employee provisioning: {} in '{}' refused: {}", n,
                ctx.institution(), r.reason().code()));
        List<EmployeeRecord> records = resolution.applyTo(found.records());
        Map<String, Set<String>> held = heldClients(targets, resolution);

        List<EmployeePlan> plans = new ArrayList<>(EmployeeAccountPlanner.plan(records, targets,
                joinKeyProps.validateFormat(), held));
        for (String number : found.missing()) {
            plans.add(ProvisionRequestRules.notFound(number, ctx.institution()));
        }
        plans.addAll(resolution.refusalPlans());

        List<ReportRow> rows = new ArrayList<>();
        if (cmd.dryRun()) {
            for (EmployeePlan p : plans) {
                rows.add(ReportRow.preview(p, resolution.accountOf(p.employeeNumber()).orElse(null)));
            }
        } else {
            String groupId = plans.stream().anyMatch(p -> p.action() == PlanAction.CREATE) ? groupId(ctx) : null;
            Map<String, EmployeeRecord> byNumber = new HashMap<>();
            records.forEach(r -> byNumber.put(r.employeeNumber(), r));
            Map<String, String> labels = new HashMap<>();
            targets.forEach(t -> labels.put(t.clientId(), Mailer.appLabel(t.clientId(), t.name())));
            for (EmployeePlan p : plans) {
                Optional<Account> existing = resolution.accountOf(p.employeeNumber());
                rows.add(apply(ctx, p, byNumber.get(p.employeeNumber()), existing.orElse(null),
                        existing.map(a -> dir.user(a.id())).orElse(null), groupId, labels, cmd.requestIp(), batchId,
                        channel));
            }
        }
        rows = ProvisionRequestRules.inRequestOrder(rows, ReportRow::employeeNumber, cmd.employeeNumbers());
        ProvisionReport report = new ProvisionReport(ctx.institution(), cmd.dryRun(), targets,
                ProvisionReport.summarise(plans, targets, rows), rows);
        var t = report.totals();
        log.info("employee access assignment: '{}' {} {} employee(s) x {} client(s) for '{}': {} created, {} existing, "
                        + "{} assigned, {} already held, {} skipped, {} failed", ctx.actor(),
                cmd.dryRun() ? "previewed" : "ran", plans.size(), targets.size(), ctx.institution(), t.created(),
                t.linked(), t.grants(), t.alreadyHeld(), t.skipped(), t.failed());
        return report;
    }

    // ---- what the admin selected -----------------------------------------------------------

    /**
     * The selected clients with the column each keys on — or 403 for the WHOLE request when one is outside
     * the caller's scope (a request that should not have been possible, not a fact about an employee). An
     * unconfigured client is loaded: it denies everybody with {@code join_column_unset} in the report.
     */
    List<ClientTarget> loadTargets(InstitutionContext ctx, List<String> wanted) {
        Map<String, ClientRepresentation> byId = new HashMap<>();
        for (ClientRepresentation c : keycloak.catalogue()) {
            if (c.getClientId() != null) {
                byId.put(c.getClientId(), c);
            }
        }
        List<ClientTarget> targets = new ArrayList<>();
        for (String clientId : wanted) {
            ClientRepresentation c = byId.get(clientId);
            if (c == null) {
                throw ApiException.notFound("Client '" + clientId + "' does not exist.");
            }
            ctx.assertClientInScope(clientId, scopes.scopeOf(c));
            targets.add(new ClientTarget(clientId, JoinKeyService.settingOf(c), c.getName()));
        }
        return targets;
    }

    private String groupId(InstitutionContext ctx) {
        return keycloak.groupIdByPath(ctx.groupPath()).orElseThrow(() -> ApiException.notFound("Institution group '"
                + ctx.groupPath() + "' not found, so a new account could not be placed in it. The institution needs "
                + "onboarding (docs/runbooks/onboard-institution.md) before its employees can be provisioned."));
    }

    /**
     * Which selected clients each resolved employee can ALREADY reach (named, or a direct member of an
     * admitted group; a denied user is NOT a holder). A client whose policies cannot be read is treated as
     * held by nobody — granting again is idempotent, a wrong "already held" skips the write.
     */
    private Map<String, Set<String>> heldClients(List<ClientTarget> targets, AccountResolver.Resolution resolution) {
        Map<String, String> userIds = new LinkedHashMap<>();
        resolution.resolved().forEach((n, a) -> {
            if (a != null && a.id() != null) {
                userIds.put(n, a.id());
            }
        });
        if (userIds.isEmpty()) {
            return Map.of();
        }
        KeycloakExec sa = access.serviceAccount();
        Map<String, HeldClients.ClientHolders> holders = new HashMap<>();
        for (ClientTarget t : targets) {
            try {
                ClientAssignment a = access.assignment(sa, t.clientId());
                holders.put(t.clientId(), new HeldClients.ClientHolders(new LinkedHashSet<>(a.userIds()),
                        new LinkedHashSet<>(a.groupIds()), new LinkedHashSet<>(a.deniedUserIds())));
            } catch (RuntimeException e) {
                log.warn("employee access assignment: could not read who already holds '{}': {}. Treating everyone "
                        + "as not holding it; the grants are idempotent.", t.clientId(), e.getMessage());
            }
        }
        return HeldClients.compute(targets, userIds, holders, keycloak::directGroupIds);
    }

    // ---- one employee ----------------------------------------------------------------------

    /** Carry out one employee's plan. Never throws — records and returns. */
    private ReportRow apply(InstitutionContext ctx, EmployeePlan plan, EmployeeRecord record, Account existing,
                            UserRepresentation existingUser, String groupId, Map<String, String> labels,
                            String requestIp, String batchId, Channel channel) {
        ReportRow row = ReportRow.of(plan, Outcome.SKIPPED, existing);
        if (!plan.proceeds() || record == null) {
            return row;
        }
        try {
            String userId;
            String passwordMode = null;
            boolean created;
            if (existing != null) {
                userId = existing.id();
                created = false;
            } else {
                userId = createUser(ctx, plan, record);
                passwordMode = devPassword() != null ? PASSWORD_SHARED_DEV : PASSWORD_SET_BY_HOLDER;
                created = true;
            }

            List<GrantOutcome> grants = grant(ctx, plan, userId, row.username(), requestIp, batchId, channel);
            List<String> granted = grants.stream().filter(g -> g.error == null).map(g -> g.clientId).toList();
            List<ProvisionViews.GrantError> errors = grants.stream().filter(g -> g.error != null)
                    .map(g -> new ProvisionViews.GrantError(g.clientId, g.error)).toList();

            if (created && groupId != null) {
                // Only for an account this flow created; an existing account keeps whatever membership
                // somebody chose for it. Membership, never how access is granted (that was admitUser above).
                keycloak.addToGroup(userId, groupId);
            }
            Map<String, Object> extras = new LinkedHashMap<>(notify(ctx, plan, userId, granted, labels, created,
                    existingUser, passwordMode));
            String linkError = mirror.link(plan.employeeNumber(), userId);
            if (passwordMode != null) {
                extras.put(ProvisionViews.PASSWORD, passwordMode);
            }
            if (!errors.isEmpty()) {
                extras.put(ProvisionViews.GRANT_ERRORS, errors);
            }
            if (linkError != null) {
                extras.put(ProvisionViews.MIRROR_ERROR, linkError);
            }
            ReportRow done = row.applied(created, userId, granted, extras);
            audit.emit(AuditEvent.of(created ? AuditEventType.ADMIN_USER_CREATED : AuditEventType.ADMIN_USER_UPDATED)
                    .actor(ctx.actor()).requestIp(requestIp).institution(ctx.institution()).targetUser(userId)
                    .put("action", "employee_access_assigned")
                    .put("target_username", done.username())
                    .put("employee_number", plan.employeeNumber())
                    .put("created", created)
                    .put("account", done.account().code())
                    .put("password", passwordMode)
                    .put("clients_granted", granted)
                    .put("clients_already_held", plan.held())
                    .put("clients_denied", plan.denied().stream().map(d -> d.clientId()).toList())
                    .put("batch_id", batchId));
            return done;
        } catch (ApiException e) {
            return failed(ctx, plan, row, existing, e.getMessage(), requestIp);
        } catch (RuntimeException e) {
            log.error("employee access assignment: {} failed: {}", plan.employeeNumber(), e.getClass().getSimpleName());
            return failed(ctx, plan, row, existing, e.getClass().getSimpleName() + ": " + e.getMessage(), requestIp);
        }
    }

    private ReportRow failed(InstitutionContext ctx, EmployeePlan plan, ReportRow row, Account existing,
                             String message, String requestIp) {
        ReportRow out = row.failed(message, existing != null);
        AuditEvent.Builder ev = AuditEvent.of(AuditEventType.ADMIN_USER_CREATED).failure().actor(ctx.actor())
                .requestIp(requestIp).institution(ctx.institution())
                .put("action", "employee_provisioning_failed")
                .put("employee_number", plan.employeeNumber())
                .put("target_username", plan.username())
                .put("error", message);
        if (existing != null) {
            ev.targetUser(existing.id());
        }
        audit.emit(ev);
        return out;
    }

    /**
     * Create the account: username = email (validated like every other identifier write: a NID/email held by
     * another account is refused), {@code UPDATE_PASSWORD} required, email unverified, then a temporary
     * credential nobody is told. With the DEBUG-only shared dev password: that password, no required action.
     */
    private String createUser(InstitutionContext ctx, EmployeePlan plan, EmployeeRecord record) {
        String shared = devPassword();
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        attributes.put("institution", List.of(ctx.institution()));
        attributes.put(AccountResolver.ATTR_EMPLOYEE_NUMBER, List.of(plan.employeeNumber()));
        if (record.nationalId() != null) {
            attributes.put("national_id", List.of(record.nationalId()));
        }
        if (record.phone() != null) {
            attributes.put("phone", List.of(record.phone()));
        }
        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(plan.username());
        rep.setEmail(plan.username());
        rep.setFirstName(record.firstName() != null ? record.firstName() : plan.username());
        rep.setLastName(record.lastName() != null ? record.lastName() : "");
        rep.setEnabled(true);
        rep.setEmailVerified(false);
        rep.setAttributes(attributes);
        rep.setRequiredActions(shared != null ? List.of() : List.of(SetupLinkPolicy.UPDATE_PASSWORD));
        joinKeys.validateIdentifierWrite(rep, null);
        String userId = keycloak.createUser(rep);
        keycloak.setPassword(userId, shared != null ? shared : TemporaryPasswords.generate(), shared == null);
        return userId;
    }

    private static final class GrantOutcome {
        final String clientId;
        final String error;

        GrantOutcome(String clientId, String error) {
            this.clientId = clientId;
            this.error = error;
        }
    }

    /**
     * Grant the clients this employee qualified for and does not hold — ONLY those ({@code plan.granted()};
     * no parameter can widen it). Per user via {@code admitUser} (which also lifts a standing denial), never
     * via the institution group. A per-client failure is collected, not raised. One audit event per
     * (user, client).
     */
    private List<GrantOutcome> grant(InstitutionContext ctx, EmployeePlan plan, String userId, String username,
                                     String requestIp, String batchId, Channel channel) {
        List<GrantOutcome> out = new ArrayList<>();
        KeycloakExec sa = access.serviceAccount();
        for (String clientId : plan.granted()) {
            try {
                access.admitUser(sa, clientId, userId);
            } catch (RuntimeException e) {
                log.error("employee access assignment: could not grant '{}' to {}: {}", clientId, userId,
                        e.getMessage());
                out.add(new GrantOutcome(clientId, e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage()));
                continue;
            }
            out.add(new GrantOutcome(clientId, null));
            audit.emit(AuditEvent.of(AuditEventType.ADMIN_CLIENT_UPDATED).actor(ctx.actor()).clientId(clientId)
                    .requestIp(requestIp).institution(ctx.institution()).targetClient(clientId).targetUser(userId)
                    .put("action", "assign_user_access")
                    .put("mode", "grant")
                    .put("channel", channel.code())
                    .put("batch_id", batchId)
                    .put("timestamp", Instant.now().toString())
                    .put("target_username", username != null ? username : plan.username())
                    .put("employee_number", plan.employeeNumber())
                    .put("account", plan.accountStatus().code()));
        }
        return out;
    }

    /**
     * ONE email, the wrapper's: a fresh account gets the welcome with its applications and the set-password
     * link ({@link SetupLinkService}, bound to the granted applications); an existing account gets "access
     * granted" (never "an account has been created"), and nothing when nothing was granted. No message ever
     * contains a password.
     */
    private Map<String, Object> notify(InstitutionContext ctx, EmployeePlan plan, String userId, List<String> granted,
                                       Map<String, String> labels, boolean created, UserRepresentation existing,
                                       String passwordMode) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean shared = PASSWORD_SHARED_DEV.equals(passwordMode);
        String username = existing != null && existing.getUsername() != null ? existing.getUsername()
                : plan.username() == null ? "" : plan.username();
        // An existing account's own address: it is where its holder actually reads mail.
        String recipient = existing != null && existing.getEmail() != null ? existing.getEmail()
                : plan.username() == null ? "" : plan.username();
        List<String> apps = granted.stream().map(c -> labels.getOrDefault(c, c)).toList();
        String name = plan.displayName();

        if (!created) {
            result.put(ProvisionViews.PASSWORD_EMAIL, EMAIL_NOT_NEEDED);
            if (!granted.isEmpty() && existing != null && existing.getRequiredActions() != null
                    && existing.getRequiredActions().contains(SetupLinkPolicy.UPDATE_PASSWORD)) {
                try {
                    setupLinks.bindClients(userId, granted);
                } catch (RuntimeException e) {
                    log.warn("employee access assignment: could not bind {} to the setup link: {}", granted,
                            e.getMessage());
                }
            }
            if (granted.isEmpty()) {
                result.put(ProvisionViews.WELCOME_EMAIL, EMAIL_NOT_NEEDED);
                return result;
            }
            DeliveryResult r = mailer.sendAccessGranted(new Mailer.AccessChangeMail(recipient, name, username, apps,
                    ctx.institution(), null));
            result.put(ProvisionViews.WELCOME_EMAIL, r.report());
            return result;
        }

        String url = null;
        if (shared) {
            result.put(ProvisionViews.PASSWORD_EMAIL, EMAIL_SKIPPED_SHARED);
        } else if (!mailer.configured()) {
            // No link is issued that nobody can be told about; the applications are bound so the admin's
            // resend (or the holder's) offers them.
            setupLinks.bindClients(userId, granted);
            setupLinks.recordDelivery(userId, SetupLinkPolicy.DELIVERY_NOT_CONFIGURED, SetupLinkPolicy.VIA_PROVISIONING);
            result.put(ProvisionViews.PASSWORD_EMAIL, SetupLinkPolicy.DELIVERY_NOT_CONFIGURED);
            result.put(ProvisionViews.WELCOME_EMAIL, SetupLinkPolicy.DELIVERY_NOT_CONFIGURED);
            return result;
        } else {
            url = setupLinks.issueUrl(userId, granted);
        }
        DeliveryResult r = mailer.sendWelcome(new Mailer.WelcomeMail(url != null ? userId : null, recipient, name,
                username, apps, ctx.institution(), null, url != null, shared, url,
                (int) SetupLinkPolicy.LINK_LIFESPAN.toHours()));
        result.put(ProvisionViews.WELCOME_EMAIL, r.report());
        if (url != null) {
            // One email carries both: the link is "sent" exactly when it is (Mailer recorded the delivery).
            result.put(ProvisionViews.PASSWORD_EMAIL, r.report());
        }
        return result;
    }

    // ---- account lookups -------------------------------------------------------------------

    /** {@link AccountResolver.AccountDirectory} over {@link ProvisioningKeycloak}, keeping each user it found. */
    private final class Directory implements AccountResolver.AccountDirectory {
        private final Map<String, UserRepresentation> users = new HashMap<>();

        UserRepresentation user(String id) {
            return users.get(id);
        }

        private Optional<Account> keep(Optional<UserRepresentation> u) {
            u.ifPresent(x -> users.put(x.getId(), x));
            return u.map(x -> Account.of(x.getId(), x.getUsername(), x.getAttributes()));
        }

        @Override
        public Optional<Account> byId(String userId) {
            Optional<UserRepresentation> u = keycloak.userById(userId);
            if (u.isEmpty()) {
                log.warn("employee access assignment: the mirror links a user Keycloak does not have; falling back "
                        + "to a lookup");
            }
            return keep(u);
        }

        @Override
        public Optional<Account> byUsername(String username) {
            return keep(keycloak.userByUsername(username));
        }

        @Override
        public Optional<Account> byEmail(String email) {
            return keep(keycloak.userByEmail(email));
        }

        @Override
        public Optional<Account> byEmployeeNumber(String employeeNumber) {
            return keep(keycloak.userByEmployeeNumber(employeeNumber));
        }
    }
}
