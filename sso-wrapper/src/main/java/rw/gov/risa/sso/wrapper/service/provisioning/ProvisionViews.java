package rw.gov.risa.sso.wrapper.service.provisioning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.BatchTotals;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ClientDecision;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ClientTarget;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ProvisionReport;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.ReportRow;

/**
 * Wire shapes of {@code POST /institution-admin/employees/provision} — field names exactly as the reference
 * ({@code schema/employee_provisioning.py}). No field can ever carry a password: {@code password} is the
 * MODE ({@code set_by_holder} / {@code shared_dev}), never a value.
 */
public final class ProvisionViews {

    private ProvisionViews() {
    }

    /** Report row extras keys (set by {@link EmployeeProvisioningService}). */
    public static final String WELCOME_EMAIL = "welcome_email";
    public static final String PASSWORD_EMAIL = "password_email";
    public static final String PASSWORD = "password";
    public static final String GRANT_ERRORS = "grant_errors";
    public static final String MIRROR_ERROR = "mirror_error";

    public record ProvisionClient(
            @JsonProperty("client_id") String clientId,
            String name,
            @JsonProperty("join_column") String joinColumn,
            @JsonProperty("employee_field") String employeeField,
            boolean configured) {
    }

    public record ClientDenial(
            @JsonProperty("client_id") String clientId,
            @JsonProperty("join_column") String joinColumn,
            boolean qualified,
            String reason,
            String message) {
    }

    public record GrantError(@JsonProperty("client_id") String clientId, String error) {
    }

    public record EmployeeReport(
            @JsonProperty("employee_number") String employeeNumber,
            String name,
            String username,
            @Schema(description = "create / link / skip") String action,
            @Schema(description = "existing / created / not_created") String account,
            String reason,
            String message,
            @JsonProperty("clients_granted") List<String> clientsGranted,
            @JsonProperty("clients_skipped") @Schema(description = "Already held — not granted again")
            List<String> clientsSkipped,
            @JsonProperty("clients_denied") List<ClientDenial> clientsDenied,
            @Schema(description = "created / linked / skipped / failed") String outcome,
            @JsonProperty("user_id") String userId,
            @JsonProperty("welcome_email") String welcomeEmail,
            @JsonProperty("password_email") String passwordEmail,
            @Schema(description = "How a created account's first password is set: set_by_holder (the set-password "
                    + "link) or shared_dev (EMPLOYEE_PROVISION_DEV_PASSWORD, DEBUG only). Never a password.")
            String password,
            @JsonProperty("grant_errors") List<GrantError> grantErrors,
            @JsonProperty("mirror_error") String mirrorError) {
    }

    public record ProvisionTotals(
            int selected, int created, int linked, int skipped, int grants, int denials, int failed,
            @JsonProperty("already_held") int alreadyHeld,
            @JsonProperty("accounts_existing") int accountsExisting,
            @JsonProperty("clients_denying_everyone") List<String> clientsDenyingEveryone) {

        static ProvisionTotals of(BatchTotals t) {
            return new ProvisionTotals(t.selected(), t.created(), t.linked(), t.skipped(), t.grants(), t.denials(),
                    t.failed(), t.alreadyHeld(), t.accountsExisting(), t.clientsDenyingEveryone());
        }
    }

    public record ProvisionReportView(
            String institution,
            @JsonProperty("dry_run") boolean dryRun,
            List<ProvisionClient> clients,
            ProvisionTotals totals,
            List<EmployeeReport> employees) {
    }

    public static ProvisionReportView of(ProvisionReport r) {
        List<ProvisionClient> clients = new ArrayList<>();
        for (ClientTarget t : r.clients()) {
            clients.add(new ProvisionClient(t.clientId(), t.name(), t.column().value(), t.employeeField(),
                    t.configured()));
        }
        List<EmployeeReport> rows = new ArrayList<>();
        for (ReportRow row : r.employees()) {
            rows.add(row(row));
        }
        return new ProvisionReportView(r.institution(), r.dryRun(), clients, ProvisionTotals.of(r.totals()), rows);
    }

    public static EmployeeReport row(ReportRow row) {
        List<ClientDenial> denied = new ArrayList<>();
        for (ClientDecision d : row.clientsDenied()) {
            denied.add(new ClientDenial(d.clientId(), d.column().value(), d.qualified(),
                    d.reason() == null ? null : d.reason().code(), d.message()));
        }
        Map<String, Object> x = row.extras();
        List<GrantError> errors = null;
        if (x.get(GRANT_ERRORS) instanceof List<?> l) {
            errors = new ArrayList<>();
            for (Object o : l) {
                if (o instanceof GrantError g) {
                    errors.add(g);
                }
            }
        }
        return new EmployeeReport(row.employeeNumber(), row.name(), row.username(), row.action().code(),
                row.account().code(), row.reason(), row.message(), row.clientsGranted(), row.clientsSkipped(), denied,
                row.outcome().code(), row.userId(), str(x.get(WELCOME_EMAIL)), str(x.get(PASSWORD_EMAIL)),
                str(x.get(PASSWORD)), errors, str(x.get(MIRROR_ERROR)));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
