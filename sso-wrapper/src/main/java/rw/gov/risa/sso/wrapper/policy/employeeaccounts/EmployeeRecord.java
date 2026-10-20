package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

import java.util.Map;
import java.util.Set;

import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumn;

/**
 * One employee as the IPPIS mirror holds them, narrowed to what a decision needs.
 *
 * <p>{@code keycloakUserId} set ⇒ assignment onto an existing account (the service resolves the
 * account <b>before</b> planning — {@link AccountResolver}); unset ⇒ creation.
 *
 * @param status {@code active} | {@code suspended} | {@code terminated} ({@code null} ⇒ active)
 */
public record EmployeeRecord(String employeeNumber, String email, String phone, String nationalId,
                             String firstName, String lastName, String institutionSlug, String status,
                             String keycloakUserId) {

    /** Employment states an account may be created for ({@code suspended} is still staff). */
    public static final Set<String> PROVISIONABLE_STATUSES = Set.of("active", "suspended");

    /**
     * Which field of an <b>employee record</b> each join column reads (stated explicitly; it
     * coincides with the Keycloak attribute names by choice only).
     */
    public static final Map<JoinColumn, String> EMPLOYEE_FIELDS = Map.of(
            JoinColumn.NID, "national_id",
            JoinColumn.PHONE, "phone",
            JoinColumn.EMAIL, "email");

    public EmployeeRecord {
        status = status == null || status.isBlank() ? "active" : status;
    }

    /**
     * Build from a mirror row (snake_case keys: employee_number, email, phone, national_id,
     * first_name, last_name, institution_slug, status, keycloak_user_id). Every value is trimmed;
     * blanks become {@code null} (status defaults to {@code active}).
     */
    public static EmployeeRecord fromRow(Map<String, ?> row) {
        return new EmployeeRecord(
                clean(row.get("employee_number")),
                blank(row.get("email")),
                blank(row.get("phone")),
                blank(row.get("national_id")),
                blank(row.get("first_name")),
                blank(row.get("last_name")),
                blank(row.get("institution_slug")),
                blank(row.get("status")),
                blank(row.get("keycloak_user_id")));
    }

    /** A name if IPPIS gave one, else the employee number. */
    public String displayName() {
        StringBuilder sb = new StringBuilder();
        for (String p : new String[] {firstName, lastName}) {
            if (p != null && !p.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(p);
            }
        }
        String name = sb.toString().strip();
        return name.isEmpty() ? employeeNumber : name;
    }

    public boolean hasAccount() {
        return keycloakUserId != null && !keycloakUserId.isEmpty();
    }

    /** Their value for one join column, trimmed; "" when empty. */
    public String valueFor(JoinColumn column) {
        String raw = switch (column) {
            case NID -> nationalId;
            case PHONE -> phone;
            case EMAIL -> email;
        };
        return raw == null ? "" : raw.strip();
    }

    /** A copy carrying the resolved account id (or {@code null} to clear a stale mirror link). */
    public EmployeeRecord withKeycloakUserId(String userId) {
        return new EmployeeRecord(employeeNumber, email, phone, nationalId, firstName, lastName, institutionSlug,
                status, userId);
    }

    static String clean(Object value) {
        return value instanceof String s ? s.strip() : "";
    }

    private static String blank(Object value) {
        String c = clean(value);
        return c.isEmpty() ? null : c;
    }
}
