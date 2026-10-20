package rw.gov.risa.sso.wrapper.service.accessmatrix;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Cell;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Column;

/**
 * Wire shapes of the access matrix routes — field names exactly as the reference
 * ({@code schema/access_matrix.py}). A full NID appears ONLY in {@link RevealedNid}.
 */
public final class MatrixViews {

    private MatrixViews() {
    }

    public record MatrixColumn(
            @JsonProperty("client_id") String clientId,
            String name,
            @Schema(description = "owned / shared") String kind,
            @Schema(description = "A: the institution's own client; C: cross-cutting, shared with it") String type,
            boolean editable,
            @Schema(description = "Why the column is read-only, when it is") String reason) {

        public static MatrixColumn of(Column c) {
            return new MatrixColumn(c.clientId(), c.name(), c.kind().code(), c.type(), c.editable(), c.reason());
        }
    }

    public record MatrixCell(
            @JsonProperty("has_access") boolean hasAccess,
            @Schema(description = "Named on the client's user policy") boolean direct,
            @JsonProperty("via_groups") @Schema(description = "Groups that admit them") List<String> viaGroups) {

        public static MatrixCell of(Cell c) {
            return new MatrixCell(c.hasAccess(), c.direct(), c.viaGroups());
        }
    }

    public record SetupEmail(
            @Schema(description = "sent / failed / not_configured") String status,
            @Schema(description = "provisioning / holder_resend / admin_resend") String via,
            String at) {
    }

    public record MatrixRow(
            @JsonProperty("employee_number") String employeeNumber,
            @Schema(description = "Their SSO account, when they have one") String sub,
            String username,
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName,
            @Schema(description = "IPPIS email, else the account's") String email,
            @Schema(description = "IPPIS gender (M/F); null when IPPIS sent none") String gender,
            @JsonProperty("nid_masked") @Schema(description = "Last four digits shown; the full value only via "
                    + "/access-matrix/nid/{employee_number}") String nidMasked,
            @JsonProperty("has_nid") boolean hasNid,
            @Schema(description = "active / terminated (no open post here: may lose access, not gain it)")
            String status,
            @Schema(description = "existing / none / elsewhere / ambiguous") String account,
            @JsonProperty("account_enabled") Boolean accountEnabled,
            @JsonProperty("locked_reason") String lockedReason,
            @JsonProperty("awaiting_password") boolean awaitingPassword,
            @JsonProperty("setup_email") SetupEmail setupEmail,
            @JsonProperty("setup_email_resendable") boolean setupEmailResendable,
            Map<String, MatrixCell> cells) {
    }

    public record AccessMatrixPage(
            String institution,
            List<MatrixColumn> columns,
            List<MatrixRow> items,
            int page,
            @JsonProperty("page_size") int pageSize,
            @Schema(description = "Rows matching the search and filter") int total,
            @JsonProperty("total_employees") int totalEmployees,
            @Schema(description = "Per client: matching rows with access") Map<String, Integer> counts) {
    }

    public record BulkItemResult(
            @JsonProperty("employee_number") String employeeNumber,
            @Schema(description = "Their account — new, when this change created it") String sub,
            @JsonProperty("client_id") String clientId,
            @Schema(description = "applied / rejected / failed") String status,
            String reason,
            String message,
            @JsonProperty("account_created") boolean accountCreated,
            @JsonProperty("setup_email") @Schema(description = "Only when this change created the account: sent / "
                    + "failed / not_configured / skipped_shared_password") String setupEmail,
            @JsonProperty("setup_email_error") String setupEmailError,
            @Schema(description = "Only for an account that already existed, when applied: sent / failed: <why> / "
                    + "not_configured / no_address / disabled") String notified) {

        public BulkItemResult withNotified(String value) {
            return new BulkItemResult(employeeNumber, sub, clientId, status, reason, message, accountCreated,
                    setupEmail, setupEmailError, value);
        }
    }

    public record BulkReport(
            String institution,
            @Schema(description = "grant / revoke") String action,
            String channel,
            @JsonProperty("batch_id") String batchId,
            List<BulkItemResult> results,
            Map<String, Integer> summary,
            @Schema(description = "People emailed about the change, counted by outcome (one email per person)")
            Map<String, Integer> notifications) {
    }

    public record RevealedNid(@JsonProperty("employee_number") String employeeNumber, String nid) {
    }

    public record SetupEmailResend(
            @JsonProperty("employee_number") String employeeNumber,
            String sub,
            @Schema(description = "sent / failed / not_configured") String status,
            String message) {
    }

    public record ImportItem(
            @Schema(description = "Spreadsheet row (1-based), when the item came from one") Integer row,
            @JsonProperty("employee_number") String employeeNumber,
            String name,
            @JsonProperty("client_id") String clientId,
            @Schema(description = "will_grant / will_create_account / already_has_access / removal_ignored / "
                    + "rejected / invalid") String outcome,
            String reason,
            String message) {
    }

    public record ImportPreviewView(
            @JsonProperty("import_id") @Schema(description = "Null when the file asks for no grant") String importId,
            @JsonProperty("expires_at") String expiresAt,
            String institution,
            @JsonProperty("approved_by") String approvedBy,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("file_sha256") String fileSha256,
            @JsonProperty("export_id") String exportId,
            @JsonProperty("exported_at") String exportedAt,
            @JsonProperty("exported_by") String exportedBy,
            List<String> clients,
            Map<String, Integer> summary,
            List<ImportItem> items) {
    }

    public record ImportReport(
            String institution,
            String action,
            String channel,
            @JsonProperty("batch_id") String batchId,
            List<BulkItemResult> results,
            Map<String, Integer> summary,
            Map<String, Integer> notifications,
            @JsonProperty("import_id") String importId,
            @JsonProperty("approved_by") String approvedBy,
            @JsonProperty("file_name") String fileName) {
    }
}
