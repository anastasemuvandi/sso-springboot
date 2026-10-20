package rw.gov.risa.sso.wrapper.service.institutionadmin;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.identity.IdentityStore;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RosterEmployee;

/**
 * Every person employed at ONE institution, one row each (port of
 * {@code employee_directory.institution_roster}) — the overview's leaver input. The institution term
 * is the isolation boundary and comes from the caller's resolved context, never a parameter;
 * {@code DISTINCT ON (employee.id)} keeps an open employment over a closed one. {@code status} is
 * {@code active} while any employment HERE has an open job, else {@code terminated}.
 */
@Component
public class InstitutionRoster {

    static final String SQL = "SELECT DISTINCT ON (e.id) e.id AS employee_number, e.first_name, e.last_name, e.email, "
            + "e.national_id, e.gender, "
            + "(CASE WHEN EXISTS (SELECT 1 FROM job j WHERE j.employment_id = em.id AND j.end_date IS NULL) "
            + "THEN 'active' ELSE 'terminated' END) AS status, l.keycloak_user_id "
            + "FROM employment em JOIN employee e ON em.employee_id = e.id "
            + "JOIN institution i ON em.institution_id = i.id "
            + "LEFT JOIN employee_sso_link l ON l.employee_id = e.id "
            + "WHERE i.short_code = :institution "
            + "ORDER BY e.id, (EXISTS (SELECT 1 FROM job j WHERE j.employment_id = em.id AND j.end_date IS NULL)) DESC";

    private final ObjectProvider<IdentityStore> store;

    public InstitutionRoster(ObjectProvider<IdentityStore> store) {
        this.store = store;
    }

    /** Empty when the mirror is not configured (the overview says so instead of reporting zero). */
    public Optional<List<RosterEmployee>> roster(String institution) {
        IdentityStore s = store.getIfAvailable();
        if (s == null) {
            return Optional.empty();
        }
        List<RosterEmployee> rows = s.jdbc().query(SQL, new MapSqlParameterSource("institution", institution),
                (rs, n) -> new RosterEmployee(rs.getString("employee_number"), rs.getString("first_name"),
                        rs.getString("last_name"), rs.getString("email"), rs.getString("national_id"),
                        rs.getString("status"), rs.getString("gender"), rs.getString("keycloak_user_id"),
                        institution));
        return Optional.of(rows);
    }
}
