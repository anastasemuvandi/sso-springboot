package rw.gov.risa.sso.wrapper.service.accessmatrix;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccessMatrix;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountMatch;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Action;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.BulkItem;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Channel;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Column;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RejectionReason;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RosterEmployee;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixSheet;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixWorkbook;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.ImportPreview;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.ParsedSheet;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.SheetColumn;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.SheetError;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.SheetMeta;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.SheetRow;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ClientDecision;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ProvisionReport;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ReportRow;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.accessmatrix.AccessMatrixService.State;
import rw.gov.risa.sso.wrapper.service.accessmatrix.ImportPlanStore.Plan;
import rw.gov.risa.sso.wrapper.service.accessmatrix.ImportPlanStore.PlannedItem;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.BulkReport;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.ImportItem;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.ImportPreviewView;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews.ImportReport;
import rw.gov.risa.sso.wrapper.service.provisioning.EmployeeProvisioningService;

/**
 * The access matrix as a spreadsheet (spec §5.14 "Excel round-trip"; port of
 * {@code services/access_matrix_transfer.py}). Policy: {@code policy.accessmatrixsheet}. Three requests:
 *
 * <ul>
 *   <li><b>export</b> — the selected apps x every employee as a protected .xlsx (identity columns locked,
 *       {@code YES} dropdowns, every text cell a STRING so formula injection is impossible, NIDs NOT exported,
 *       an HMAC over the institution/apps/date frame with {@code ACCESS_MATRIX_SIGNING_KEY}). Audited
 *       {@code admin.access_matrix.exported}: personal data leaving the system.</li>
 *   <li><b>preview</b> — every mark judged against the realm as it is NOW; nothing written but the plan
 *       (Redis, 30 minutes) listing every grant and refusal. Refused whole: bad signature (400
 *       {@code not_exported_here}), another institution's file (403), older than 30 days (400).</li>
 *   <li><b>apply</b> — THE STORED PLAN (never the file again), once, only by the admin who previewed it,
 *       through {@link AccessMatrixService#bulk} so every item is re-checked at apply time; channel
 *       {@code bulk}, the import id as {@code batch_id}. Grant only: a cleared YES is reported
 *       ({@code removal_ignored}) and never revoked. Audited {@code admin.access_matrix.imported}.</li>
 * </ul>
 */
@Service
public class AccessMatrixTransferService {

    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private final AccessMatrixService matrix;
    private final EmployeeProvisioningService provisioning;
    private final ImportPlanStore plans;
    private final AccessMatrixProperties props;
    private final AuditEmitter audit;
    private final Clock clock;

    @Autowired
    public AccessMatrixTransferService(AccessMatrixService matrix, EmployeeProvisioningService provisioning,
                                       ImportPlanStore plans, AccessMatrixProperties props, AuditEmitter audit) {
        this(matrix, provisioning, plans, props, audit, Clock.systemUTC());
    }

    public AccessMatrixTransferService(AccessMatrixService matrix, EmployeeProvisioningService provisioning,
                                       ImportPlanStore plans, AccessMatrixProperties props, AuditEmitter audit,
                                       Clock clock) {
        this.matrix = matrix;
        this.provisioning = provisioning;
        this.plans = plans;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
    }

    /** The workbook bytes and the download name. */
    public record Export(byte[] data, String filename, String exportId) {
    }

    private String secret() {
        String s = props.signingKey();
        if (s == null || s.isBlank()) {
            throw ApiException.unavailable(ErrorCodes.NOT_CONFIGURED,
                    "Spreadsheet export/import is not configured (ACCESS_MATRIX_SIGNING_KEY is not set).");
        }
        return s;
    }

    // ---- export ------------------------------------------------------------------------------

    public Export export(InstitutionContext ctx, CallerPrincipal caller, List<String> clientIds, String requestIp) {
        ctx.assertCapability(Capability.VIEW_MEMBERS);
        ctx.assertCapability(Capability.VIEW_CLIENTS);
        String secret = secret();
        State st = matrix.state(ctx, caller, clientIds);
        List<RosterEmployee> employees = new ArrayList<>(st.rows().employees());
        employees.sort(AccessMatrix.SORT_ORDER);
        List<SheetRow> rows = new ArrayList<>();
        for (RosterEmployee e : employees) {
            AccountMatch m = st.rows().accounts().getOrDefault(e.employeeNumber(), AccountMatch.NONE);
            UserRepresentation user = m.userId() == null ? null : st.rows().members().get(m.userId());
            String nid = AccessMatrixService.nidOf(e, user);
            String locked = AccessMatrix.ROW_LOCK_MESSAGES.get(m.state());
            if (locked == null && e.isLeaver()) {
                locked = RejectionReason.EMPLOYEE_LEFT.message(Action.GRANT);
            }
            Map<String, Boolean> cells = new LinkedHashMap<>();
            for (Column c : st.columns().columns()) {
                cells.put(c.clientId(), st.has(c.clientId(), m.userId()));
            }
            rows.add(new SheetRow(e.employeeNumber(), e.firstName(), e.lastName(),
                    e.email() != null ? e.email() : user == null ? null : user.getEmail(), e.status(), m.state(),
                    nid != null && !nid.isBlank(), cells, locked));
        }
        SheetMeta meta = new SheetMeta(ctx.institution(),
                st.columns().columns().stream().map(Column::clientId).toList(), AccessMatrix.newBatchId(),
                SECONDS.format(Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC)),
                ctx.actor());
        byte[] data = AccessMatrixWorkbook.build(meta, st.columns().columns().stream()
                .map(c -> new SheetColumn(c.clientId(), c.name(), c.editable())).toList(), rows, secret);
        audit.emit(AuditEvent.of(AuditEventType.ACCESS_MATRIX_EXPORTED).actor(ctx.actor()).requestIp(requestIp)
                .institution(ctx.institution())
                .put("target", meta.exportId())
                .put("export_id", meta.exportId())
                .put("clients", meta.clients())
                .put("rows", rows.size())
                .put("file_sha256", AccessMatrixSheet.fileSha256(data)));
        return new Export(data, AccessMatrixSheet.filenameFor(ctx.institution(), meta.exportedAt()), meta.exportId());
    }

    // ---- preview -----------------------------------------------------------------------------

    public ImportPreviewView preview(InstitutionContext ctx, CallerPrincipal caller, byte[] data, String fileName,
                                     String approvedBy) {
        ctx.assertCapability(Capability.VIEW_MEMBERS);
        ctx.assertCapability(Capability.MANAGE_CLIENTS);
        String approver = ImportPreview.normaliseApprover(approvedBy);
        ImportPreview.approverRefusal(approver).ifPresent(this::refuse);
        String secret = secret();
        ParsedSheet parsed;
        try {
            parsed = AccessMatrixWorkbook.parse(data == null ? new byte[0] : data, secret);
        } catch (SheetError e) {
            throw ApiException.badRequest(e.code(), e.getMessage());
        }
        ImportPreview.frameRefusal(parsed, ctx.institution(), Instant.now(clock)).ifPresent(this::refuse);

        // Which of the file's applications are still in scope: one that left it refuses the whole file (403).
        Map<String, Boolean> editable = new LinkedHashMap<>();
        Map<String, rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService.VisibleClient> apps =
                matrix.visibleApps(ctx);
        for (String c : parsed.clients()) {
            if (apps.containsKey(c)) {
                editable.put(c, apps.get(c).manageable());
            }
        }
        if (editable.size() < parsed.clients().size()) {
            ImportPreview.Result r = ImportPreview.plan(parsed, editable, Map.of(), Map.of(), (c, u) -> false, false,
                    w -> List.of());
            if (r instanceof ImportPreview.Refused refused) {
                refuse(refused.refusal());
            }
        }
        State st = matrix.state(ctx, caller, parsed.clients());
        Map<String, RosterEmployee> roster = st.rows().roster();
        boolean mayCreate = ctx.checkCapability(Capability.MANAGE_MEMBERS).isEmpty()
                && ctx.checkCapability(Capability.MANAGE_MEMBERSHIP).isEmpty();

        ImportPreview.Result result = ImportPreview.plan(parsed, editable, roster, st.rows().accounts(), st::has,
                mayCreate, wanted -> dryRunCreation(ctx, wanted));
        if (result instanceof ImportPreview.Refused refused) {
            refuse(refused.refusal());
        }
        ImportPreview.Preview preview = ((ImportPreview.Planned) result).preview();

        SheetMeta meta = parsed.meta();
        String digest = AccessMatrixSheet.fileSha256(data);
        String importId = null;
        String expiresAt = null;
        if (!preview.plan().isEmpty()) {
            importId = AccessMatrix.newBatchId();
            expiresAt = SECONDS.format(Instant.now(clock).plus(ImportPreview.PLAN_TTL).truncatedTo(ChronoUnit.SECONDS)
                    .atOffset(ZoneOffset.UTC));
            plans.save(importId, new Plan(ctx.institution(), ctx.actor(), approver, fileName, digest, meta.exportId(),
                    meta.exportedAt(), meta.exportedBy(), parsed.clients(), preview.plan().stream()
                    .map(i -> new PlannedItem(i.employeeNumber(), i.clientId())).toList()));
        }
        List<ImportItem> items = preview.items().stream().map(i -> new ImportItem(i.row(), i.employeeNumber(),
                i.name(), i.clientId(), i.outcome(), i.reason(), i.message())).toList();
        return new ImportPreviewView(importId, expiresAt, ctx.institution(), approver, fileName, digest,
                meta.exportId(), meta.exportedAt(), meta.exportedBy(), parsed.clients(), preview.summary(), items);
    }

    /**
     * Provisioning's own dry run for the people with no account, folded per (employee, client) — so a
     * missing NID for an NID-keyed app, or no email to name an account after, shows in the preview rather
     * than after confirm. Writes nothing.
     */
    private List<ImportPreview.CreationVerdict> dryRunCreation(InstitutionContext ctx, Map<String, List<String>> wanted) {
        List<ImportPreview.CreationVerdict> out = new ArrayList<>();
        for (Map.Entry<List<String>, List<String>> g : ImportPreview.dryRunBatches(wanted, provisioning.maxBatch())) {
            ProvisionReport report = provisioning.run(ctx, new EmployeeProvisioningService.Command(g.getValue(),
                    g.getKey(), true, null, null, Channel.BULK));
            Map<String, ReportRow> rows = new HashMap<>();
            report.employees().forEach(r -> rows.put(r.employeeNumber(), r));
            for (String number : g.getValue()) {
                ReportRow row = rows.get(number);
                Map<String, String[]> denied = new HashMap<>();
                if (row != null) {
                    for (ClientDecision d : row.clientsDenied()) {
                        denied.put(d.clientId(), new String[] {d.reason() == null ? null : d.reason().code(),
                                d.message()});
                    }
                }
                out.addAll(ImportPreview.foldDryRun(number, g.getKey(),
                        row == null ? Set.of() : Set.copyOf(row.clientsGranted()), denied,
                        row == null ? null : row.reason(), row == null ? null : row.message()));
            }
        }
        return out;
    }

    // ---- apply -------------------------------------------------------------------------------

    public ImportReport apply(InstitutionContext ctx, CallerPrincipal caller, String importId, String requestIp) {
        ctx.assertCapability(Capability.VIEW_MEMBERS);
        ctx.assertCapability(Capability.MANAGE_CLIENTS);
        ApiException gone = ApiException.notFound("This import is no longer waiting: it was already applied, or the "
                + "preview is more than " + ImportPreview.PLAN_TTL.toMinutes() + " minutes old. Upload the file again.");
        Plan plan = plans.get(importId).orElseThrow(() -> gone);
        if (!ctx.institution().equals(plan.institution()) || !ctx.actor().equals(plan.actor())) {
            // Not consumed: it is still the previewing admin's to confirm.
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN,
                    "Only the administrator who previewed this import can confirm it.");
        }
        if (!plans.consume(importId)) {
            throw gone;
        }
        List<BulkItem> items = plan.items().stream().map(i -> new BulkItem(i.employeeNumber(), i.clientId())).toList();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("import_id", importId);
        extra.put("approved_by", plan.approvedBy());
        BulkReport report = matrix.bulk(ctx, caller, new AccessMatrixService.BulkCommand(Action.GRANT.code(), items,
                Channel.BULK.code(), requestIp, importId, extra));
        audit.emit(AuditEvent.of(AuditEventType.ACCESS_MATRIX_IMPORTED).actor(ctx.actor()).requestIp(requestIp)
                .institution(ctx.institution())
                .put("target", importId)
                .put("import_id", importId)
                .put("batch_id", importId)
                .put("approved_by", plan.approvedBy())
                .put("file_name", plan.fileName())
                .put("file_sha256", plan.fileSha256())
                .put("export_id", plan.exportId())
                .put("exported_at", plan.exportedAt())
                .put("exported_by", plan.exportedBy())
                .put("clients", plan.clients())
                .put("requested", items.size())
                .put("summary", report.summary()));
        return new ImportReport(report.institution(), report.action(), report.channel(), report.batchId(),
                report.results(), report.summary(), report.notifications(), importId, plan.approvedBy(),
                plan.fileName());
    }

    private void refuse(ImportPreview.Refusal r) {
        throw new ApiException(HttpStatus.valueOf(r.httpStatus()), r.code(), r.message());
    }
}
