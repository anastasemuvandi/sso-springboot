package rw.gov.risa.sso.wrapper.service.provisioning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.identity.IdentityStore;
import rw.gov.risa.sso.wrapper.identity.MatchState;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RosterEmployee;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.EmployeeRecord;

/**
 * The IPPIS mirror as provisioning and the access matrix read it (reference {@code load_records} and
 * {@code employee_directory.institution_roster}), plus the one write provisioning makes to it (the account
 * link). Nothing read here decides authorisation; it decides WHO may be selected.
 *
 * <ul>
 *   <li><b>The institution term is unconditional</b> and comes from the resolved context (never a request
 *       parameter): an employee is this institution's because they hold an EMPLOYMENT here. Another
 *       institution's employee is simply absent — indistinguishable from one that does not exist.</li>
 *   <li>{@code DISTINCT ON (employee.id)}: one record per person (the unit is an account), an open
 *       employment preferred.</li>
 *   <li>{@code status} is derived: {@code active} while an employment here has an open job, else
 *       {@code terminated}.</li>
 * </ul>
 * No mirror configured ({@code IDENTITY_DATABASE_URL} unset) → 503 {@code identity_db_not_configured}.
 */
@Component
public class EmployeeMirror {

    private static final Logger log = LoggerFactory.getLogger(EmployeeMirror.class);

    static final String OPEN_JOB = "EXISTS (SELECT 1 FROM job j WHERE j.employment_id = em.id AND j.end_date IS NULL)";
    static final String STATUS = "(CASE WHEN " + OPEN_JOB + " THEN 'active' ELSE 'terminated' END)";
    static final String FROM = " FROM employment em JOIN employee e ON em.employee_id = e.id "
            + "JOIN institution i ON em.institution_id = i.id "
            + "LEFT JOIN employee_sso_link l ON l.employee_id = e.id ";

    private final ObjectProvider<IdentityStore> store;

    public EmployeeMirror(ObjectProvider<IdentityStore> store) {
        this.store = store;
    }

    /** True when the mirror is configured. */
    public boolean available() {
        return store.getIfAvailable() != null;
    }

    /** The selected employees from {@code institution}'s slice (order not guaranteed; see ProvisionRequestRules.order). */
    public List<EmployeeRecord> records(String institution, List<String> employeeNumbers) {
        IdentityStore s = require();
        if (employeeNumbers.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT DISTINCT ON (e.id) e.id AS employee_number, e.email, e.phone, e.national_id, "
                + "e.first_name, e.last_name, i.short_code AS institution_slug, " + STATUS + " AS status, "
                + "l.keycloak_user_id" + FROM
                + "WHERE e.id IN (:numbers) AND i.short_code = :institution "
                + "ORDER BY e.id, " + OPEN_JOB + " DESC";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("numbers", new ArrayList<>(new LinkedHashSet<>(employeeNumbers)))
                .addValue("institution", institution);
        return s.jdbc().query(sql, p, (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            for (String c : List.of("employee_number", "email", "phone", "national_id", "first_name", "last_name",
                    "institution_slug", "status", "keycloak_user_id")) {
                row.put(c, rs.getString(c));
            }
            return EmployeeRecord.fromRow(row);
        });
    }

    /** Every person employed at {@code institution}, one row each — the access matrix's rows. */
    public List<RosterEmployee> roster(String institution) {
        IdentityStore s = require();
        String sql = "SELECT DISTINCT ON (e.id) e.id AS employee_number, e.first_name, e.last_name, e.email, "
                + "e.national_id, e.gender, " + STATUS + " AS status, l.keycloak_user_id" + FROM
                + "WHERE i.short_code = :institution ORDER BY e.id, " + OPEN_JOB + " DESC";
        return s.jdbc().query(sql, new MapSqlParameterSource("institution", institution), (rs, n) ->
                new RosterEmployee(rs.getString("employee_number"), rs.getString("first_name"),
                        rs.getString("last_name"), rs.getString("email"), rs.getString("national_id"),
                        rs.getString("status"), rs.getString("gender"), rs.getString("keycloak_user_id"),
                        institution));
    }

    /**
     * Record the account on the employee's link row (an upsert: the sync normally wrote the row, but an
     * UPDATE matching nothing would fail silently). Returns an error text, or null. Never throws: the account
     * exists and its access is granted; a lost link self-heals on the next sync.
     */
    public String link(String employeeNumber, String userId) {
        IdentityStore s = store.getIfAvailable();
        if (s == null) {
            return "the employee mirror is not configured";
        }
        try {
            s.jdbc().update("INSERT INTO employee_sso_link (employee_id, keycloak_user_id, match_state, updated_at) "
                    + "VALUES (:employee, :user, :state, now()) ON CONFLICT ON CONSTRAINT uq_employee_sso_link_employee "
                    + "DO UPDATE SET keycloak_user_id = EXCLUDED.keycloak_user_id, match_state = EXCLUDED.match_state, "
                    + "updated_at = now()", new MapSqlParameterSource().addValue("employee", employeeNumber)
                    .addValue("user", userId).addValue("state", MatchState.MATCHED.wire()));
            return null;
        } catch (RuntimeException e) {
            String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("employee provisioning: could not link {} to user {} in the mirror: {}", employeeNumber, userId,
                    detail);
            return detail;
        }
    }

    private IdentityStore require() {
        IdentityStore s = store.getIfAvailable();
        if (s == null) {
            throw ApiException.unavailable(ErrorCodes.IDENTITY_DB_NOT_CONFIGURED,
                    "The employee mirror has no database (IDENTITY_DATABASE_URL is not set). "
                            + "See docs/runbooks/create-sso-identity-db.md.");
        }
        return s;
    }
}
