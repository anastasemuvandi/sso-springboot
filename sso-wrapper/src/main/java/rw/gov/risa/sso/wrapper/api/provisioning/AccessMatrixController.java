package rw.gov.risa.sso.wrapper.api.provisioning;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.BulkItem;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Channel;
import rw.gov.risa.sso.wrapper.policy.accessmatrixsheet.AccessMatrixSheet;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.accessmatrix.AccessMatrixService;
import rw.gov.risa.sso.wrapper.service.accessmatrix.AccessMatrixTransferService;
import rw.gov.risa.sso.wrapper.service.accessmatrix.MatrixViews;
import rw.gov.risa.sso.wrapper.service.institution.CurrentInstitution;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * The employee x client access matrix (spec §5.14): the page, the full-NID reveal, the admin's set-password
 * resend, bulk grant/revoke and the Excel round-trip. Rows are the caller's institution's employees from the
 * IPPIS mirror (503 {@code identity_db_not_configured} without it). The institution comes from the caller's
 * own grant.
 */
@RestController
@RequestMapping("/institution-admin")
@Tag(name = "Institution admin — access matrix")
public class AccessMatrixController {

    static final String INSTITUTION_PARAM = "Target institution slug. Required only if you administer more than one "
            + "institution, or are a platform admin acting for a specific one. Naming one you do not administer is "
            + "refused (403), never honoured.";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final AccessMatrixService matrix;
    private final AccessMatrixTransferService transfer;

    public AccessMatrixController(AccessMatrixService matrix, AccessMatrixTransferService transfer) {
        this.matrix = matrix;
        this.transfer = transfer;
    }

    /** One (employee, application) change. Keys as the reference: {@code employeeNumber}, {@code clientId}. */
    public record BulkItemRequest(
            @JsonProperty("employeeNumber") @NotNull @Schema(description = "IPPIS employee number")
            String employeeNumber,
            @JsonProperty("clientId") @NotNull String clientId) {
    }

    public record BulkRequest(
            @NotNull @Schema(description = "grant / revoke") String action,
            @NotNull List<@Valid BulkItemRequest> items,
            @Schema(description = "ui (default) / bulk") String channel) {
    }

    private static List<String> split(String clients) {
        return clients == null ? List.of() : Arrays.stream(clients.split(",")).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
    }

    @GetMapping("/access-matrix")
    @RateLimit("60/minute")
    @Operation(summary = "One page of the matrix: your institution's employees x the selected apps",
            description = "A cell has_access exactly when the token gate would admit the person. Locked rows: "
                    + "leavers (may lose access, not gain it) and accounts outside the institution or shared by two "
                    + "employees. NIDs are masked; GET /access-matrix/nid/{employee_number} reveals one (audited).")
    public MatrixViews.AccessMatrixPage page(
            @AuthenticationPrincipal CallerPrincipal caller,
            @Parameter(hidden = true) @CurrentInstitution(require = Capability.VIEW_CLIENTS) InstitutionContext ctx,
            @Parameter(description = "Comma-separated client ids (the selected columns)") @RequestParam String clients,
            @Parameter(description = INSTITUTION_PARAM) @RequestParam(required = false) String institution,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(name = "page_size", defaultValue = "50") Integer pageSize,
            @Parameter(description = "Search by first/last name, employee number, email or NID")
            @RequestParam(required = false) String q,
            @Parameter(description = "has / none — filter rows by access") @RequestParam(required = false)
            String access,
            @Parameter(description = "With access: about this client only; without, any selected client")
            @RequestParam(name = "access_client", required = false) String accessClient,
            @Parameter(description = "M / F / unknown (IPPIS sent none)") @RequestParam(required = false)
            String gender) {
        return matrix.matrix(ctx, caller, split(clients), page, pageSize, q, access, accessClient, gender);
    }

    @PostMapping("/grants/bulk")
    @RateLimit("30/minute")
    @Operation(summary = "Grant or revoke many (employee, app) pairs at once; per-item results",
            description = "403, nothing changed, if any item names an employee outside your institution or an app "
                    + "outside its scope. Otherwise each item stands alone: applied (audited, one batch_id), "
                    + "rejected/state_changed (the cell changed since your page loaded — grant only adds, revoke only "
                    + "removes), rejected/not_manageable, failed. A grant to someone with no account goes through "
                    + "employee provisioning under the same batch id.")
    public MatrixViews.BulkReport bulk(
            @AuthenticationPrincipal CallerPrincipal caller,
            @Parameter(hidden = true) @CurrentInstitution(require = Capability.MANAGE_CLIENTS) InstitutionContext ctx,
            @Parameter(description = INSTITUTION_PARAM) @RequestParam(required = false) String institution,
            @Valid @RequestBody BulkRequest body) {
        String channel = body.channel() == null || body.channel().isBlank() ? Channel.UI.code() : body.channel();
        if (Channel.fromCode(channel).map(c -> !c.isRequestChannel()).orElse(false)) {
            throw ApiException.badRequest("invalid_channel", "channel must be 'ui' or 'bulk'.");
        }
        List<BulkItem> items = body.items().stream().map(i -> new BulkItem(i.employeeNumber(), i.clientId())).toList();
        return matrix.bulk(ctx, caller, new AccessMatrixService.BulkCommand(body.action(), items, channel,
                RequestInfo.clientIp(), null, null));
    }

    @GetMapping("/access-matrix/nid/{employee_number}")
    @RateLimit("60/minute")
    @Operation(summary = "The full national ID of one of your institution's employees",
            description = "Every call is audited as admin.employee.nid_revealed. 404 for anyone not in your "
                    + "institution's employee list.")
    public MatrixViews.RevealedNid nid(
            @AuthenticationPrincipal CallerPrincipal caller,
            @Parameter(hidden = true) @CurrentInstitution(require = Capability.VIEW_MEMBERS) InstitutionContext ctx,
            @Parameter(description = INSTITUTION_PARAM) @RequestParam(required = false) String institution,
            @PathVariable("employee_number") String employeeNumber) {
        return matrix.revealNid(ctx, caller, employeeNumber, RequestInfo.clientIp());
    }

    @PostMapping("/access-matrix/setup-email/{employee_number}")
    @RateLimit(value = "10/minute", key = RateLimit.Key.CALLER)
    @Operation(summary = "Email an employee's account a new set-password link, when the last email did NOT go out",
            description = "Only after a failed / not_configured delivery (otherwise 409, nothing sent: the holder "
                    + "resends from the sign-in page). 404 off-roster. 10/min per caller. Audited "
                    + "admin.setup_link.resend.")
    public MatrixViews.SetupEmailResend setupEmail(
            @AuthenticationPrincipal CallerPrincipal caller,
            @Parameter(hidden = true) @CurrentInstitution(require = Capability.MANAGE_MEMBERS) InstitutionContext ctx,
            @Parameter(description = INSTITUTION_PARAM) @RequestParam(required = false) String institution,
            @PathVariable("employee_number") String employeeNumber) {
        return matrix.resendSetupEmail(ctx, caller, employeeNumber, RequestInfo.clientIp());
    }

    @GetMapping(value = "/access-matrix/export")
    @RateLimit("10/minute")
    @Operation(summary = "Every employee x the selected apps, as a protected Excel workbook",
            description = "YES marks who has access today; identity columns, view-only apps and locked rows are "
                    + "locked; app cells carry a YES dropdown; every text cell is a string. NIDs are not exported. A "
                    + "hidden sheet carries the institution, apps and date, HMAC-signed. Audited "
                    + "admin.access_matrix.exported.",
            responses = @ApiResponse(responseCode = "200", description = "The matrix as .xlsx",
                    content = @Content(mediaType = XLSX)))
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal CallerPrincipal caller,
            @Parameter(hidden = true) @CurrentInstitution(require = Capability.VIEW_CLIENTS) InstitutionContext ctx,
            @Parameter(description = "Comma-separated client ids (the selected columns)") @RequestParam String clients,
            @Parameter(description = INSTITUTION_PARAM) @RequestParam(required = false) String institution) {
        AccessMatrixTransferService.Export x = transfer.export(ctx, caller, split(clients), RequestInfo.clientIp());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + x.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(x.data());
    }

    @PostMapping(value = "/access-matrix/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit("10/minute")
    @Operation(summary = "Read an edited export and say what importing it would do. Writes nothing",
            description = "Every YES is judged against the realm as it is now: will_grant, will_create_account, "
                    + "already_has_access (counted), removal_ignored (a cleared YES — imports never revoke), rejected, "
                    + "invalid. The grants are kept as a plan for 30 minutes under import_id; confirming applies that "
                    + "plan, not the file. 400 for a file that is not an untampered export (not_exported_here) or is "
                    + "over 30 days old; 403 for another institution's file or an app no longer in scope.")
    public MatrixViews.ImportPreviewView preview(
            @AuthenticationPrincipal CallerPrincipal caller,
            @Parameter(hidden = true) @CurrentInstitution(require = Capability.MANAGE_CLIENTS) InstitutionContext ctx,
            @Parameter(description = INSTITUTION_PARAM) @RequestParam(required = false) String institution,
            @Parameter(description = "The edited export (.xlsx)") @RequestPart("file") MultipartFile file,
            @Parameter(description = "Who signed off these grants — recorded on every one")
            @RequestParam(name = "approved_by", required = false, defaultValue = "") String approvedBy) {
        byte[] data;
        try (InputStream in = file.getInputStream()) {
            data = in.readNBytes(AccessMatrixSheet.MAX_FILE_BYTES + 1);
        } catch (IOException e) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "The uploaded file could not be read.");
        }
        return transfer.preview(ctx, caller, data, file.getOriginalFilename(), approvedBy);
    }

    @PostMapping("/access-matrix/import/{import_id}/apply")
    @RateLimit("10/minute")
    @Operation(summary = "Carry out a previewed import — once, and only by the admin who previewed it",
            description = "Applies the STORED plan (never the file) through the bulk path with channel bulk and the "
                    + "import id as batch_id, so each grant is checked again, reported per item and audited; one "
                    + "admin.access_matrix.imported event summarises the file. 404 when the plan was already applied "
                    + "or has expired.")
    public MatrixViews.ImportReport apply(
            @AuthenticationPrincipal CallerPrincipal caller,
            @Parameter(hidden = true) @CurrentInstitution(require = Capability.MANAGE_CLIENTS) InstitutionContext ctx,
            @Parameter(description = INSTITUTION_PARAM) @RequestParam(required = false) String institution,
            @PathVariable("import_id") String importId) {
        return transfer.apply(ctx, caller, importId, RequestInfo.clientIp());
    }
}
