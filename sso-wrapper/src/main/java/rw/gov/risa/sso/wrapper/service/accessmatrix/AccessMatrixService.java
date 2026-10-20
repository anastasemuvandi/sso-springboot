package rw.gov.risa.sso.wrapper.service.accessmatrix;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.error.KeycloakErrors;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccessMatrix;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountMatch;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountState;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Action;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.BulkItem;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.BulkPlan;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.BulkPlanner;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.BulkRequestRefusal;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Cell;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Channel;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Column;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Holder;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.ItemResult;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.ItemStatus;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.MemberAccount;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.PageBounds;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.PlannedWrite;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RejectionReason;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RosterEmployee;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.ImportPreview;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.AdmitManyResult;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.RevokeResult;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.RevokeTarget;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.AccountStatus;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ClientDecision;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ProvisionReport;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ReportRow;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionRefusedException;
import rw.gov.risa.sso.wrapper.policy.setuplink.Delivery;
import rw.gov.risa.sso.wrapper.policy.setuplink.SetupLinkPolicy;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.AccessMatrixPage;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.BulkItemResult;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.BulkReport;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.MatrixCell;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.MatrixColumn;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.MatrixRow;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.RevealedNid;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.SetupEmail;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.SetupEmailResend;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService.VisibleClient;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;
import rw.gov.risa.sso.wrapper.service.provisioning.AccessChangeNotifier;
import rw.gov.risa.sso.wrapper.service.provisioning.EmployeeMirror;
import rw.gov.risa.sso.wrapper.service.provisioning.EmployeeProvisioningService;
import rw.gov.risa.sso.wrapper.service.provisioning.ProvisionViews;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService;

/**
 * The employee x client access matrix (spec §5.14; port of {@code InstitutionAdminService.access_matrix},
 * {@code bulk_access}, {@code reveal_nid}, {@code resend_setup_email}). Rules: {@code policy.accessmatrix}.
 *
 * <ul>
 *   <li><b>Rows are the institution's EMPLOYEES from the mirror</b>, matched to the institution's members in
 *       memory ({@link AccessMatrix#resolveAccounts}); an account outside the institution or shared by two
 *       employees locks the row, a leaver may lose access but not gain it.</li>
 *   <li><b>NIDs are masked</b>; {@link #revealNid} is the only way to a full one, audited per call.</li>
 *   <li><b>Bulk</b>: any item outside the institution (employee or client) → 403 for the whole request,
 *       nothing written; otherwise per item, with the server-side concurrency guard (grant only adds,
 *       revoke only removes, a changed cell → {@code rejected/state_changed}); one policy write per client;
 *       a grant to someone with no account goes through provisioning under the same batch id; every applied
 *       change audited; one email per person ({@link AccessChangeNotifier}).</li>
 * </ul>
 */
@Service
public class AccessMatrixService {

    private static final Logger log = LoggerFactory.getLogger(AccessMatrixService.class);

    private final MatrixKeycloak keycloak;
    private final EmployeeMirror mirror;
    private final EmployeeProvisioningService provisioning;
    private final SetupLinkService setupLinks;
    private final AccessChangeNotifier notifier;
    private final AuditEmitter audit;

    public AccessMatrixService(MatrixKeycloak keycloak, EmployeeMirror mirror, EmployeeProvisioningService provisioning,
                               SetupLinkService setupLinks, AccessChangeNotifier notifier, AuditEmitter audit) {
        this.keycloak = keycloak;
        this.mirror = mirror;
        this.provisioning = provisioning;
        this.setupLinks = setupLinks;
        this.notifier = notifier;
        this.audit = audit;
    }

    // ---- shared reads ------------------------------------------------------------------------

    /** The selected columns and the apps they came from. */
    public record Columns(List<Column> columns, Map<String, VisibleClient> apps) {
        public boolean crossCutting(String clientId) {
            VisibleClient v = apps.get(clientId);
            return v != null && v.scope().crossCutting();
        }
    }

    /** Roster, members and the account match of every employee. */
    public record Rows(List<RosterEmployee> employees, Map<String, UserRepresentation> members,
                       Map<String, AccountMatch> accounts) {
        public Map<String, RosterEmployee> roster() {
            Map<String, RosterEmployee> m = new LinkedHashMap<>();
            employees.forEach(e -> m.put(e.employeeNumber(), e));
            return m;
        }
    }

    /** Everything one matrix read needs. */
    public record State(Columns columns, Rows rows, Map<String, Map<String, Holder>> holders) {
        public boolean has(String clientId, String userId) {
            return userId != null && AccessMatrix.hasAccess(holders.getOrDefault(clientId, Map.of()).get(userId));
        }
    }

    /**
     * The selected clients as columns, in the order asked; 400 for none or more than
     * {@value AccessMatrix#MAX_COLUMNS}; 403 for any the caller may not see at all.
     */
    public Columns columns(InstitutionContext ctx, Collection<String> clientIds) {
        List<String> selected = new ArrayList<>(new LinkedHashSet<>(clientIds.stream()
                .filter(c -> c != null && !c.isBlank()).map(String::strip).toList()));
        if (selected.isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "Select at least one application.");
        }
        if (selected.size() > AccessMatrix.MAX_COLUMNS) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                    "At most " + AccessMatrix.MAX_COLUMNS + " applications at a time.");
        }
        Map<String, VisibleClient> apps = visibleApps(ctx);
        List<String> outside = selected.stream().filter(c -> !apps.containsKey(c)).toList();
        if (!outside.isEmpty()) {
            throw ApiException.forbidden(BulkRequestRefusal.CLIENT_OUTSIDE_SCOPE.code(), "Application(s) " + outside
                    + " are not in institution '" + ctx.institution() + "''s scope. Nothing was read.",
                    Map.of("clients", outside));
        }
        List<Column> columns = new ArrayList<>();
        for (String c : selected) {
            VisibleClient v = apps.get(c);
            columns.add(AccessMatrix.columnFor(c, v.client().getName(), v.scope().crossCutting(), v.manageable()));
        }
        return new Columns(columns, apps);
    }

    /** Every client the institution may see, by client id. */
    public Map<String, VisibleClient> visibleApps(InstitutionContext ctx) {
        Map<String, VisibleClient> apps = new LinkedHashMap<>();
        for (VisibleClient v : keycloak.visibleClients(ctx)) {
            apps.put(v.clientId(), v);
        }
        return apps;
    }

    /** The institution's roster from the mirror, its members, and who resolves to which account. */
    public Rows rows(InstitutionContext ctx, CallerPrincipal caller) {
        List<RosterEmployee> employees = mirror.roster(ctx.institution());
        Map<String, UserRepresentation> members = keycloak.members(ctx, caller);
        Map<String, MemberAccount> accounts = new LinkedHashMap<>();
        members.forEach((id, u) -> accounts.put(id, memberAccount(u)));
        return new Rows(employees, members, AccessMatrix.resolveAccounts(employees, accounts));
    }

    public State state(InstitutionContext ctx, CallerPrincipal caller, Collection<String> clientIds) {
        Columns cols = columns(ctx, clientIds);
        Rows rows = rows(ctx, caller);
        Map<String, Map<String, Holder>> holders = new LinkedHashMap<>();
        for (Column c : cols.columns()) {
            holders.put(c.clientId(), keycloak.holders(ctx, caller, c.clientId(), cols.crossCutting(c.clientId()),
                    rows.members()));
        }
        return new State(cols, rows, holders);
    }

    public static MemberAccount memberAccount(UserRepresentation u) {
        return new MemberAccount(u.getId(), u.getUsername(), u.getEmail(), !Boolean.FALSE.equals(u.isEnabled()),
                u.getAttributes(), u.getRequiredActions());
    }

    // ---- the page ----------------------------------------------------------------------------

    /** One page of the matrix. NIDs are masked here, never sent in full. */
    public AccessMatrixPage matrix(InstitutionContext ctx, CallerPrincipal caller, List<String> clientIds, Integer page,
                                   Integer pageSize, String q, String access, String accessClient, String gender) {
        ctx.assertCapability(Capability.VIEW_MEMBERS);
        ctx.assertCapability(Capability.VIEW_CLIENTS);
        Columns cols = columns(ctx, clientIds);
        if (accessClient != null && !accessClient.isBlank()
                && cols.columns().stream().noneMatch(c -> c.clientId().equals(accessClient))) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                    "access_client must be one of the selected applications.");
        }
        Rows rows = rows(ctx, caller);
        Map<String, Map<String, Holder>> holders = new LinkedHashMap<>();
        for (Column c : cols.columns()) {
            holders.put(c.clientId(), keycloak.holders(ctx, caller, c.clientId(), cols.crossCutting(c.clientId()),
                    rows.members()));
        }

        record Line(RosterEmployee e, AccountMatch match, Map<String, Cell> cells) {
        }
        List<Line> lines = new ArrayList<>();
        for (RosterEmployee e : rows.employees()) {
            if (!AccessMatrix.matches(e, q) || !AccessMatrix.genderFilter(e, gender)) {
                continue;
            }
            AccountMatch m = rows.accounts().getOrDefault(e.employeeNumber(), AccountMatch.NONE);
            Map<String, Cell> cells = new LinkedHashMap<>();
            for (Column c : cols.columns()) {
                cells.put(c.clientId(), m.userId() == null ? Cell.NONE
                        : AccessMatrix.cell(holders.get(c.clientId()).get(m.userId())));
            }
            if (!AccessMatrix.accessFilter(cells, access, blankToNull(accessClient))) {
                continue;
            }
            lines.add(new Line(e, m, cells));
        }
        lines.sort((a, b) -> AccessMatrix.SORT_ORDER.compare(a.e(), b.e()));

        PageBounds bounds = AccessMatrix.pageBounds(page, pageSize, lines.size());
        List<MatrixRow> items = new ArrayList<>();
        for (Line l : lines.subList(Math.min(bounds.start(), lines.size()),
                Math.min(bounds.start() + bounds.size(), lines.size()))) {
            UserRepresentation user = l.match().userId() == null ? null : rows.members().get(l.match().userId());
            String nid = nidOf(l.e(), user);
            boolean awaiting = l.match().state() == AccountState.EXISTING
                    && SetupLinkPolicy.awaitingPassword(SetupLinkService.account(user));
            Delivery delivery = awaiting ? setupLinks.lastDelivery(user.getId()).orElse(null) : null;
            boolean resendable = awaiting
                    && SetupLinkPolicy.adminResendRefusal(SetupLinkService.account(user), delivery, null).isEmpty();
            Map<String, MatrixCell> cells = new LinkedHashMap<>();
            l.cells().forEach((k, v) -> cells.put(k, MatrixCell.of(v)));
            String g = l.e().gender() == null ? "" : l.e().gender().strip().toUpperCase(Locale.ROOT);
            items.add(new MatrixRow(l.e().employeeNumber(), l.match().userId(),
                    user == null ? null : user.getUsername(), l.e().firstName(), l.e().lastName(),
                    l.e().email() != null ? l.e().email() : user == null ? null : user.getEmail(),
                    g.isEmpty() ? null : g, AccessMatrix.maskNid(nid), nid != null && !nid.isBlank(), l.e().status(),
                    l.match().state().code(), user == null ? null : !Boolean.FALSE.equals(user.isEnabled()),
                    AccessMatrix.ROW_LOCK_MESSAGES.get(l.match().state()), awaiting,
                    delivery == null ? null : new SetupEmail(delivery.status(), delivery.via(), delivery.at()),
                    resendable, cells));
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Column c : cols.columns()) {
            counts.put(c.clientId(), (int) lines.stream().filter(l -> l.cells().get(c.clientId()).hasAccess()).count());
        }
        return new AccessMatrixPage(ctx.institution(), cols.columns().stream().map(MatrixColumn::of).toList(), items,
                bounds.page(), bounds.size(), lines.size(), rows.employees().size(), counts);
    }

    /** The NID the mirror holds, else the account's {@code national_id} attribute. */
    static String nidOf(RosterEmployee e, UserRepresentation user) {
        if (e.nationalId() != null && !e.nationalId().isBlank()) {
            return e.nationalId();
        }
        return user == null ? null : AccessMatrix.nidOf(memberAccount(user));
    }

    // ---- one employee ------------------------------------------------------------------------

    /**
     * The full NID of one employee of the institution. Audited EVERY call ({@code admin.employee.nid_revealed}).
     * 404 for anyone not on the roster — another institution's employee is indistinguishable from a fiction.
     */
    public RevealedNid revealNid(InstitutionContext ctx, CallerPrincipal caller, String employeeNumber,
                                 String requestIp) {
        ctx.assertCapability(Capability.VIEW_MEMBERS);
        Rows rows = rows(ctx, caller);
        RosterEmployee e = rows.roster().get(employeeNumber);
        if (e == null) {
            throw ApiException.notFound("No employee '" + employeeNumber + "' in this institution.");
        }
        String uid = rows.accounts().getOrDefault(employeeNumber, AccountMatch.NONE).userId();
        String nid = nidOf(e, uid == null ? null : rows.members().get(uid));
        audit.emit(AuditEvent.of(AuditEventType.EMPLOYEE_NID_REVEALED).actor(ctx.actor()).requestIp(requestIp)
                .institution(ctx.institution()).targetUser(uid)
                .put("employee_number", employeeNumber)
                .put("had_nid", nid != null && !nid.isBlank())
                .put("timestamp", Instant.now().toString()));
        return new RevealedNid(employeeNumber, nid);
    }

    /**
     * Send an employee's account a new set-password link because the last email to it did NOT go out
     * ({@code manage-members}). 404 off-roster; 409 (nothing sent) when the account is not one this applies
     * to; 10/min per caller (429) and the {@code admin.setup_link.resend} audit live in
     * {@link SetupLinkService#adminResend}.
     */
    public SetupEmailResend resendSetupEmail(InstitutionContext ctx, CallerPrincipal caller, String employeeNumber,
                                             String requestIp) {
        ctx.assertCapability(Capability.VIEW_MEMBERS);
        ctx.assertCapability(Capability.MANAGE_MEMBERS);
        Rows rows = rows(ctx, caller);
        if (!rows.roster().containsKey(employeeNumber)) {
            throw ApiException.notFound("No employee '" + employeeNumber + "' in this institution.");
        }
        AccountMatch m = rows.accounts().getOrDefault(employeeNumber, AccountMatch.NONE);
        if (m.state() != AccountState.EXISTING || m.userId() == null) {
            String msg = AccessMatrix.ROW_LOCK_MESSAGES.get(m.state());
            throw ApiException.conflict(ErrorCodes.CONFLICT,
                    msg != null ? msg : SetupLinkPolicy.ADMIN_RESEND_MESSAGES.get(SetupLinkPolicy.NO_ACCOUNT),
                    Map.of("reason", m.state() == AccountState.NONE ? SetupLinkPolicy.NO_ACCOUNT : m.state().code()));
        }
        SetupLinkService.AdminResend r = setupLinks.adminResend(m.userId(), ctx.actor(), ctx.institution(), requestIp);
        String status;
        String message;
        switch (r.outcome()) {
            case SetupLinkPolicy.SENT -> {
                status = SetupLinkPolicy.DELIVERY_SENT;
                message = "A new set-password link was emailed. Any earlier link no longer works.";
            }
            case SetupLinkPolicy.SEND_FAILED -> {
                status = SetupLinkPolicy.DELIVERY_FAILED;
                message = "The email could not be sent. Check the mail relay and try again.";
            }
            case SetupLinkPolicy.MAIL_NOT_CONFIGURED -> {
                status = SetupLinkPolicy.DELIVERY_NOT_CONFIGURED;
                message = "No mail relay is configured, so nothing was sent.";
            }
            default -> throw ApiException.conflict(ErrorCodes.CONFLICT,
                    SetupLinkPolicy.ADMIN_RESEND_MESSAGES.getOrDefault(r.outcome(), r.outcome()),
                    Map.of("reason", r.outcome()));
        }
        return new SetupEmailResend(employeeNumber, m.userId(), status, message);
    }

    // ---- bulk --------------------------------------------------------------------------------

    /**
     * One bulk change. {@code batchId}/{@code auditExtra}: for a caller that is itself one audited act (the
     * spreadsheet import passes its import id and who approved it).
     */
    public record BulkCommand(String action, List<BulkItem> items, String channel, String requestIp, String batchId,
                              Map<String, Object> auditExtra) {
    }

    public BulkReport bulk(InstitutionContext ctx, CallerPrincipal caller, BulkCommand cmd) {
        int n = cmd.items() == null ? 0 : cmd.items().size();
        BulkPlanner.validateRequest(cmd.action(), cmd.channel(), n).ifPresent(r -> {
            throw new ApiException(HttpStatus.valueOf(r.httpStatus()), r.code(), r.message());
        });
        Action action = Action.fromCode(cmd.action()).orElseThrow();
        Channel channel = Channel.fromCode(cmd.channel()).orElseThrow();
        ctx.assertCapability(Capability.VIEW_MEMBERS);
        ctx.assertCapability(Capability.MANAGE_CLIENTS);

        Columns cols = columns(ctx, AccessMatrix.dedupeItems(cmd.items()).unique().stream()
                .map(BulkItem::clientId).toList());
        Rows rows = rows(ctx, caller);
        Map<String, RosterEmployee> roster = rows.roster();
        // Whole-request 403 BEFORE any holder read or write.
        Set<String> outside = new java.util.TreeSet<>();
        for (BulkItem it : cmd.items()) {
            if (!roster.containsKey(it.employeeNumber())) {
                outside.add(String.valueOf(it.employeeNumber()));
            }
        }
        if (!outside.isEmpty()) {
            throw ApiException.forbidden(BulkRequestRefusal.EMPLOYEE_OUTSIDE_INSTITUTION.code(), "Employee(s) "
                    + outside + " are not in " + ctx.institution() + "'s employee list. Nothing was changed.",
                    Map.of("employees", List.copyOf(outside)));
        }
        Map<String, Map<String, Holder>> holders = new LinkedHashMap<>();
        Map<String, Column> columns = new LinkedHashMap<>();
        for (Column c : cols.columns()) {
            columns.put(c.clientId(), c);
            holders.put(c.clientId(), keycloak.holders(ctx, caller, c.clientId(), cols.crossCutting(c.clientId()),
                    rows.members()));
        }
        boolean mayCreate = ctx.checkCapability(Capability.MANAGE_MEMBERS).isEmpty()
                && ctx.checkCapability(Capability.MANAGE_MEMBERSHIP).isEmpty();

        BulkPlan plan = BulkPlanner.plan(action, cmd.items(), columns, roster, rows.accounts(), holders, mayCreate);
        if (plan instanceof BulkPlan.Forbidden f) {
            throw ApiException.forbidden(f.refusal().code(), f.refusal().message(), Map.of("outside", f.outside()));
        }
        BulkPlan.Ready ready = (BulkPlan.Ready) plan;
        String batchId = cmd.batchId() != null ? cmd.batchId() : AccessMatrix.newBatchId();
        List<BulkItemResult> results = new ArrayList<>();
        ready.rejected().forEach(r -> results.add(result(r)));

        AccessChangeNotifier.Batch notices = notifier.begin(action == Action.GRANT ? AccessChangeNotifier.Kind.GRANT
                : AccessChangeNotifier.Kind.REVOKE, ctx.institution());
        String ts = Instant.now().toString();
        for (Map.Entry<String, List<PlannedWrite>> w : ready.writes().entrySet()) {
            String clientId = w.getKey();
            boolean shared = cols.crossCutting(clientId);
            List<PlannedWrite> todo = w.getValue();
            Set<String> done = new LinkedHashSet<>();
            Map<String, Map<String, Object>> how = new HashMap<>();
            try {
                List<String> ids = todo.stream().map(PlannedWrite::userId).toList();
                if (action == Action.GRANT) {
                    AdmitManyResult r = keycloak.admit(caller, clientId, shared, ids);
                    done.addAll(r.admitted());
                    done.addAll(r.already());
                    done.forEach(uid -> how.put(uid, Map.of("named", true)));
                } else {
                    List<RevokeTarget> targets = todo.stream().map(p -> new RevokeTarget(p.userId(),
                            holders.get(clientId).getOrDefault(p.userId(), new Holder(false, null, null, false))
                                    .groupIds())).toList();
                    RevokeResult r = keycloak.revoke(caller, clientId, shared, targets);
                    done.addAll(r.unassigned());
                    done.addAll(r.denied());
                    done.forEach(uid -> how.put(uid, Map.of("unassigned", r.unassigned().contains(uid),
                            "denied", r.denied().contains(uid))));
                }
            } catch (ApiException | WebApplicationException | ProcessingException e) {
                String detail = e instanceof WebApplicationException wae
                        ? KeycloakErrors.message(wae, "Keycloak refused the write") : e.getMessage();
                log.error("access matrix: {} on '{}' failed for {} user(s): {}", action.code(), clientId, todo.size(),
                        detail);
                BulkPlanner.failed(clientId, todo, detail).forEach(r -> results.add(result(r)));
                continue;
            }
            for (ItemResult r : BulkPlanner.settle(action, clientId, todo, done)) {
                results.add(result(r));
                if (r.status() != ItemStatus.APPLIED) {
                    continue;
                }
                UserRepresentation u = rows.members().get(r.sub());
                if (u != null) {
                    notices.add(AccessChangeNotifier.Recipient.of(u), clientId,
                            Mailer.appLabel(clientId, cols.apps().get(clientId).client().getName()));
                }
                AuditEvent.Builder ev = AuditEvent.of(AuditEventType.ADMIN_CLIENT_UPDATED).actor(ctx.actor())
                        .clientId(clientId).requestIp(cmd.requestIp()).institution(ctx.institution())
                        .targetClient(clientId).targetUser(r.sub())
                        .put("action", action == Action.GRANT ? "grant_user_access" : "revoke_user_access")
                        .put("mode", action.code())
                        .put("channel", channel.code())
                        .put("batch_id", batchId)
                        .put("target_username", u == null ? null : u.getUsername())
                        .put("employee_number", r.employeeNumber())
                        .put("cross_cutting", shared)
                        .put("timestamp", ts)
                        .putAll(how.getOrDefault(r.sub(), Map.of()));
                if (cmd.auditExtra() != null) {
                    ev.putAll(cmd.auditExtra());
                }
                audit.emit(ev);
            }
        }

        if (!ready.toProvision().isEmpty()) {
            grantByProvisioning(ctx, ready.toProvision(), results, batchId, channel, cmd.requestIp());
        }

        Map<String, String> notified = notices.flush();
        List<BulkItemResult> out = new ArrayList<>();
        for (BulkItemResult r : results) {
            if (ItemStatus.APPLIED.code().equals(r.status()) && !r.accountCreated() && r.sub() != null
                    && notified.containsKey(r.sub())) {
                out.add(r.withNotified(notified.get(r.sub())));
            } else {
                out.add(r);
            }
        }
        return new BulkReport(ctx.institution(), action.code(), channel.code(), batchId, out, summarize(out),
                AccessChangeNotifier.summarize(notified));
    }

    static Map<String, Integer> summarize(List<BulkItemResult> results) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ItemStatus s : ItemStatus.values()) {
            out.put(s.code(), 0);
        }
        results.forEach(r -> out.merge(r.status(), 1, Integer::sum));
        return out;
    }

    private static BulkItemResult result(ItemResult r) {
        return new BulkItemResult(r.employeeNumber(), r.sub(), r.clientId(), r.status().code(), r.reason(),
                r.message(), false, null, null, null);
    }

    /**
     * Grants to employees with no account, through provisioning (its own rules unchanged: join key per
     * (employee, client), no account for a leaver or a missing email), grouped by the exact set of clients
     * wanted and chunked at the batch limit. Folded back per (employee, client): granted → applied; already
     * held → {@code state_changed}; denied → rejected with provisioning's reason; grant error → failed;
     * anything else → rejected with the row's reason.
     */
    private void grantByProvisioning(InstitutionContext ctx, Map<String, List<String>> wanted,
                                     List<BulkItemResult> results, String batchId, Channel channel, String requestIp) {
        for (Map.Entry<List<String>, List<String>> group : ImportPreview.dryRunBatches(wanted, provisioning.maxBatch())) {
            List<String> clients = group.getKey();
            List<String> chunk = group.getValue();
            ProvisionReport report;
            try {
                report = provisioning.run(ctx, new EmployeeProvisioningService.Command(chunk, clients, false,
                        requestIp, batchId, channel));
            } catch (ApiException | InstitutionRefusedException e) {
                for (String number : chunk) {
                    for (String c : clients) {
                        results.add(new BulkItemResult(number, null, c, ItemStatus.FAILED.code(), "provisioning_error",
                                e.getMessage(), false, null, null, null));
                    }
                }
                continue;
            }
            Map<String, ReportRow> byNumber = new HashMap<>();
            report.employees().forEach(r -> byNumber.put(r.employeeNumber(), r));
            for (String number : chunk) {
                ReportRow row = byNumber.get(number);
                Set<String> granted = row == null ? Set.of() : new LinkedHashSet<>(row.clientsGranted());
                Set<String> skipped = row == null ? Set.of() : new LinkedHashSet<>(row.clientsSkipped());
                Map<String, ClientDecision> denied = new HashMap<>();
                Map<String, String> errors = new HashMap<>();
                boolean created = false;
                String setupEmail = null;
                String setupError = null;
                String sub = null;
                if (row != null) {
                    row.clientsDenied().forEach(d -> denied.put(d.clientId(), d));
                    if (row.extras().get(ProvisionViews.GRANT_ERRORS) instanceof List<?> l) {
                        for (Object o : l) {
                            if (o instanceof ProvisionViews.GrantError g) {
                                errors.put(g.clientId(), g.error());
                            }
                        }
                    }
                    sub = row.userId();
                    created = row.account() == AccountStatus.CREATED;
                    if (created) {
                        String email = String.valueOf(row.extras().getOrDefault(ProvisionViews.PASSWORD_EMAIL, ""));
                        setupEmail = SetupLinkPolicy.deliveryStatus(email);
                        if (setupEmail == null && EmployeeProvisioningService.EMAIL_SKIPPED_SHARED.equals(email)) {
                            setupEmail = email;
                        }
                        if (SetupLinkPolicy.DELIVERY_FAILED.equals(setupEmail)) {
                            String e = email.contains(":") ? email.substring(email.indexOf(':') + 1).strip() : "";
                            setupError = e.isEmpty() ? null : e;
                        }
                    }
                }
                for (String c : clients) {
                    String status;
                    String reason = null;
                    String message = null;
                    if (row != null && row.outcome() == rw.gov.risa.sso.wrapper.policy.employeeaccounts.Outcome.FAILED) {
                        // A failed row keeps its PLANNED clients_granted (policy quirk) — none was applied.
                        status = ItemStatus.FAILED.code();
                        reason = "provisioning_error";
                        message = row.message();
                    } else if (granted.contains(c)) {
                        status = ItemStatus.APPLIED.code();
                    } else if (skipped.contains(c)) {
                        status = ItemStatus.REJECTED.code();
                        reason = RejectionReason.STATE_CHANGED.code();
                        message = RejectionReason.STATE_CHANGED.message(Action.GRANT);
                    } else if (denied.containsKey(c)) {
                        ClientDecision d = denied.get(c);
                        status = ItemStatus.REJECTED.code();
                        reason = d.reason() == null ? "denied" : d.reason().code();
                        message = d.message();
                    } else if (errors.containsKey(c)) {
                        status = ItemStatus.FAILED.code();
                        reason = ItemResult.REASON_KEYCLOAK_ERROR;
                        message = errors.get(c);
                    } else {
                        status = ItemStatus.REJECTED.code();
                        reason = row != null && row.reason() != null ? row.reason() : "not_provisioned";
                        message = row != null && row.message() != null && !row.message().isEmpty() ? row.message()
                                : "No account could be created for this employee.";
                    }
                    results.add(new BulkItemResult(number, sub, c, status, reason, message, created, setupEmail,
                            setupError, null));
                }
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
