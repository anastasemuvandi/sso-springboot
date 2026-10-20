package rw.gov.risa.sso.wrapper.service.directory;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Wire shapes of the employee directory — field names exactly as the reference
 * ({@code api/employees.py}): the listing is snake_case, the detail uses the IPPIS feed's own
 * camelCase. {@code national_id} is never in a listing; {@code nationalId} only in the detail.
 */
public final class EmployeeViews {
    private EmployeeViews() {
    }

    /** One EMPLOYMENT row (a dual appointee appears once per institution). */
    public record EmployeeRow(
            String id,
            @JsonProperty("employee_number") String employeeNumber,
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName,
            @Schema(description = "Work email as IPPIS holds it — the username an account would be created with; "
                    + "null means the employee cannot be provisioned") String email,
            String phone,
            String position,
            @Schema(description = "Derived employment status at this institution: active / terminated") String status,
            @JsonProperty("institution_code") String institutionCode,
            @JsonProperty("institution_slug") String institutionSlug,
            @JsonProperty("keycloak_user_id") String keycloakUserId,
            @JsonProperty("match_state") String matchState,
            @JsonProperty("source_updated_at") String sourceUpdatedAt,
            @JsonProperty("first_seen_at") String firstSeenAt,
            @JsonProperty("last_seen_at") String lastSeenAt,
            @JsonProperty("last_synced_at") String lastSyncedAt,
            @JsonProperty("last_sync_status") String lastSyncStatus,
            @JsonProperty("last_sync_error") String lastSyncError) {
    }

    /** Counts over the filtered set, ignoring the account filter itself. */
    public record EmployeeSummary(
            long total,
            long linked,
            long unlinked,
            long ambiguous,
            long unmapped,
            @JsonProperty("no_email") @Schema(description = "No account and no email in IPPIS — none can be created")
            long noEmail) {
    }

    public record EmployeePage(
            String institution,
            boolean scoped,
            long total,
            int limit,
            int offset,
            EmployeeSummary summary,
            List<EmployeeRow> items) {
    }

    public record JobOut(
            String id,
            String positionId,
            String positionName,
            String unitId,
            String unitName,
            String level,
            @JsonProperty("hasSubordinates") boolean hasSubordinates,
            String employeeGroup,
            @JsonProperty("isSecondaryEmployment") boolean isSecondaryEmployment,
            @JsonProperty("isActing") boolean isActing,
            String startDate,
            String endDate,
            String reasonForChange,
            String createdAt,
            String updatedAt) {
    }

    public record EmploymentOut(
            String employerId,
            String employerName,
            String sectorId,
            String sectorName,
            String institutionSlug,
            @JsonProperty("isOnboarded") boolean isOnboarded,
            List<JobOut> jobs) {
    }

    public record EmployeeAccountOut(
            String keycloakUserId,
            String matchState,
            String firstSeenAt,
            String lastSeenAt,
            String lastSyncedAt,
            String lastSyncStatus,
            String lastSyncError) {
    }

    /** One employee, shaped like the IPPIS feed; the ONLY place {@code nationalId} is returned. */
    public record EmployeeDetail(
            String id,
            String nationalId,
            String rssbNumber,
            String phone,
            String email,
            String firstName,
            String lastName,
            String gender,
            String maritalStatus,
            String dateOfBirth,
            String createdAt,
            String updatedAt,
            List<EmploymentOut> employments,
            EmployeeAccountOut account,
            @Schema(description = "True when employments was filtered to the caller's own institution") boolean scoped) {
    }
}
