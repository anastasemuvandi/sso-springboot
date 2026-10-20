package rw.gov.risa.sso.wrapper.service.directory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.identity.EmployeeEntity;
import rw.gov.risa.sso.wrapper.identity.EmployeeSsoLinkEntity;
import rw.gov.risa.sso.wrapper.identity.EmploymentEntity;
import rw.gov.risa.sso.wrapper.identity.IdentityStore;
import rw.gov.risa.sso.wrapper.identity.InstitutionEntity;
import rw.gov.risa.sso.wrapper.identity.JobEntity;
import rw.gov.risa.sso.wrapper.identity.MatchState;
import rw.gov.risa.sso.wrapper.identity.SectorEntity;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.DirectoryScope;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.directory.EmployeeViews.EmployeeAccountOut;
import rw.gov.risa.sso.wrapper.service.directory.EmployeeViews.EmployeeDetail;
import rw.gov.risa.sso.wrapper.service.directory.EmployeeViews.EmployeePage;
import rw.gov.risa.sso.wrapper.service.directory.EmployeeViews.EmployeeRow;
import rw.gov.risa.sso.wrapper.service.directory.EmployeeViews.EmployeeSummary;
import rw.gov.risa.sso.wrapper.service.directory.EmployeeViews.EmploymentOut;
import rw.gov.risa.sso.wrapper.service.directory.EmployeeViews.JobOut;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionContextService;

/**
 * The employee directory — the read side of the IPPIS mirror (port of
 * {@code services/employee_directory.py}).
 *
 * <ul>
 *   <li><b>One row per EMPLOYMENT.</b> A dual appointee appears under each ministry.</li>
 *   <li><b>The institution term comes from the resolved scope</b>
 *       ({@link InstitutionContextService#resolveDirectoryScope}), never a raw parameter, and is
 *       ANDed first — no other filter can widen past it. A platform admin reads every institution
 *       (or one they name); a delegated admin only their own.</li>
 *   <li>Rows whose employer has no slug belong to nobody: invisible to every scoped read.</li>
 *   <li>{@code national_id} is never selected for a listing; the detail is the only place it is
 *       returned, and a scoped caller's detail lists only their own institution's employments
 *       (404, never 403, for another institution's employee).</li>
 *   <li>Nothing here is consulted by an authorisation path.</li>
 * </ul>
 */
@Service
public class EmployeeDirectoryService {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_TERMINATED = "terminated";
    public static final List<String> STATUSES = List.of("active", "suspended", "terminated");
    public static final List<String> ACCOUNT_STATES = List.of("linked", "unlinked", "ambiguous");
    public static final int MAX_LIMIT = 200;
    public static final int DEFAULT_LIMIT = 50;
    public static final String DEFAULT_SORT = "employee_number";

    private static final String POSITION = "(SELECT j.position_name FROM job j WHERE j.employment_id = em.id "
            + "ORDER BY (j.end_date IS NULL) DESC, j.start_date DESC, j.ippis_job_id DESC LIMIT 1)";
    private static final String STATUS = "(CASE WHEN EXISTS (SELECT 1 FROM job j WHERE j.employment_id = em.id "
            + "AND j.end_date IS NULL) THEN 'active' ELSE 'terminated' END)";
    private static final String FROM = " FROM employment em JOIN employee e ON em.employee_id = e.id "
            + "JOIN institution i ON em.institution_id = i.id "
            + "LEFT JOIN employee_sso_link l ON l.employee_id = e.id ";

    /** Sort whitelist: a caller string never reaches ORDER BY as anything but one of these. */
    public static final Map<String, String> SORT_COLUMNS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("employee_number", "e.id");
        m.put("name", "e.last_name");
        m.put("institution", "i.short_code");
        m.put("position", POSITION);
        m.put("status", STATUS);
        m.put("first_seen_at", "l.first_seen_at");
        m.put("last_seen_at", "l.last_seen_at");
        m.put("last_synced_at", "l.last_synced_at");
        SORT_COLUMNS = java.util.Collections.unmodifiableMap(m);
    }

    private final ObjectProvider<IdentityStore> store;
    private final InstitutionContextService institutions;

    public EmployeeDirectoryService(ObjectProvider<IdentityStore> store, InstitutionContextService institutions) {
        this.store = store;
        this.institutions = institutions;
    }

    /** Scope first (an unauthorised caller is told so, not handed a database diagnostic). */
    public DirectoryScope scope(CallerPrincipal caller, String requestedInstitution) {
        return institutions.resolveDirectoryScope(caller, requestedInstitution);
    }

    public EmployeePage list(DirectoryScope scope, String search, String status, String account, boolean unmapped,
                             String sort, boolean descending, int limit, int offset) {
        if (status != null && !STATUSES.contains(status)) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.VALIDATION_ERROR,
                    "status must be one of " + STATUSES + ".");
        }
        if (account != null && !ACCOUNT_STATES.contains(account)) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.VALIDATION_ERROR,
                    "account must be one of " + ACCOUNT_STATES + ", not '" + account + "'.");
        }
        if (sort != null && !SORT_COLUMNS.containsKey(sort)) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.VALIDATION_ERROR,
                    "sort must be one of " + SORT_COLUMNS.keySet() + ".");
        }
        IdentityStore s = requireStore();
        EmployeeFilters f = new EmployeeFilters(scope.institution(), blankToNull(search), status, account, unmapped);
        int lim = Math.max(1, Math.min(limit, MAX_LIMIT));
        int off = Math.max(0, offset);

        MapSqlParameterSource p = new MapSqlParameterSource();
        List<String> where = conditions(f, true, p);
        String col = SORT_COLUMNS.getOrDefault(sort == null ? DEFAULT_SORT : sort, "e.id");
        String sql = "SELECT em.id AS id, e.id AS employee_number, e.first_name, e.last_name, e.email, e.phone, "
                + POSITION + " AS position, " + STATUS + " AS status, i.ippis_institution_id AS institution_code, "
                + "i.short_code AS institution_slug, l.keycloak_user_id, l.match_state, e.source_updated_at, "
                + "l.first_seen_at, l.last_seen_at, l.last_synced_at, l.last_sync_status, l.last_sync_error"
                + FROM + whereClause(where)
                // The tie-break ends at the ROW's own key (the employment) so paging is total.
                + " ORDER BY " + col + (descending ? " DESC" : " ASC") + " NULLS LAST, em.id ASC"
                + " LIMIT :limit OFFSET :offset";
        p.addValue("limit", lim).addValue("offset", off);

        List<EmployeeRow> items = s.jdbc().query(sql, p, (rs, n) -> row(rs));
        MapSqlParameterSource cp = new MapSqlParameterSource();
        Long total = s.jdbc().queryForObject("SELECT count(*)" + FROM + whereClause(conditions(f, true, cp)), cp,
                Long.class);
        EmployeeSummary summary = summarise(s, f);
        return new EmployeePage(f.institution(), f.institution() != null, total == null ? 0 : total, lim, off,
                summary, items);
    }

    /** Counts over the set the account tabs count WITHIN (the account filter is ignored). */
    EmployeeSummary summarise(IdentityStore s, EmployeeFilters f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = "SELECT count(*) AS total, "
                + "count(*) FILTER (WHERE l.keycloak_user_id IS NOT NULL) AS linked, "
                + "count(*) FILTER (WHERE l.keycloak_user_id IS NULL) AS unlinked, "
                + "count(*) FILTER (WHERE l.match_state = 'ambiguous') AS ambiguous, "
                + "count(*) FILTER (WHERE i.short_code IS NULL) AS unmapped, "
                + "count(*) FILTER (WHERE l.keycloak_user_id IS NULL AND e.email IS NULL) AS no_email"
                + FROM + whereClause(conditions(f, false, p));
        return s.jdbc().queryForObject(sql, p, (rs, n) -> new EmployeeSummary(rs.getLong("total"),
                rs.getLong("linked"), rs.getLong("unlinked"), rs.getLong("ambiguous"), rs.getLong("unmapped"),
                rs.getLong("no_email")));
    }

    /** The WHERE terms. The institution term is FIRST and unconditional when scoped. */
    static List<String> conditions(EmployeeFilters f, boolean includeAccount, MapSqlParameterSource p) {
        List<String> c = new ArrayList<>();
        if (f.institution() != null) {
            c.add("i.short_code = :institution");
            p.addValue("institution", f.institution());
        }
        if (f.search() != null) {
            c.add("(e.id ILIKE :q ESCAPE '\\' OR e.first_name ILIKE :q ESCAPE '\\' OR e.last_name ILIKE :q ESCAPE '\\'"
                    + " OR e.email ILIKE :q ESCAPE '\\' OR " + POSITION + " ILIKE :q ESCAPE '\\'"
                    + " OR concat(coalesce(e.first_name, ''), ' ', coalesce(e.last_name, '')) ILIKE :q ESCAPE '\\')");
            p.addValue("q", "%" + likeEscape(f.search()) + "%");
        }
        if (f.status() != null) {
            c.add(STATUS + " = :status");
            p.addValue("status", f.status());
        }
        if (f.unmapped()) {
            c.add("i.short_code IS NULL");
        }
        if (includeAccount && f.account() != null) {
            switch (f.account()) {
                case "linked" -> c.add("l.keycloak_user_id IS NOT NULL");
                case "unlinked" -> c.add("l.keycloak_user_id IS NULL");
                case "ambiguous" -> c.add("l.match_state = 'ambiguous'");
                default -> {
                }
            }
        }
        return c;
    }

    // ---- detail ----------------------------------------------------------------------------------

    /**
     * One employee in full. 404 for "not visible to you" exactly as for "does not exist" — a 403
     * would confirm the number is real. A scoped caller sees only their institution's employments.
     */
    public EmployeeDetail detail(DirectoryScope scope, String employeeNumber) {
        IdentityStore s = requireStore();
        String number = employeeNumber == null ? "" : employeeNumber.strip();
        Optional<EmployeeDetail> d = number.isEmpty() ? Optional.empty()
                : s.readOnlyTx().execute(t -> load(s, scope.institution(), number));
        return d.orElseThrow(() -> ApiException.notFound("No employee '" + employeeNumber + "' in this directory."));
    }

    private Optional<EmployeeDetail> load(IdentityStore s, String institution, String number) {
        Optional<EmployeeEntity> found = s.employees().findById(number);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<EmploymentEntity> rows = institution == null ? s.employments().findByEmployeeId(number)
                : s.employments().findByEmployeeIdAndInstitutionSlug(number, institution);
        if (institution != null && rows.isEmpty()) {
            return Optional.empty();
        }
        EmployeeEntity e = found.get();
        Map<String, String> sectors = new LinkedHashMap<>();
        for (SectorEntity sec : s.sectors().findAll()) {
            sectors.put(sec.getId(), sec.getName());
        }
        List<EmploymentOut> employments = new ArrayList<>();
        rows.stream().sorted(Comparator.comparing(r -> r.getInstitution().getIppisInstitutionId())).forEach(em -> {
            InstitutionEntity inst = em.getInstitution();
            List<JobOut> jobs = s.jobs().history(em.getId()).stream().map(EmployeeDirectoryService::job).toList();
            employments.add(new EmploymentOut(inst.getIppisInstitutionId(), inst.getName(), inst.getSectorId(),
                    inst.getSectorId() == null ? null : sectors.get(inst.getSectorId()), inst.getShortCode(),
                    inst.isOnboarded(), jobs));
        });
        Optional<EmployeeSsoLinkEntity> link = s.links().findByEmployeeId(number);
        EmployeeAccountOut account = link.map(l -> new EmployeeAccountOut(l.getKeycloakUserId(), l.getMatchState(),
                stamp(l.getFirstSeenAt()), stamp(l.getLastSeenAt()), stamp(l.getLastSyncedAt()), l.getLastSyncStatus(),
                l.getLastSyncError())).orElse(new EmployeeAccountOut(null, MatchState.UNMATCHED.wire(), null, null, null,
                null, null));
        return Optional.of(new EmployeeDetail(e.getId(), e.getNationalId(), e.getRssbNumber(), e.getPhone(),
                e.getEmail(), e.getFirstName(), e.getLastName(), e.getGender(), e.getMaritalStatus(),
                date(e.getDateOfBirth()), stamp(e.getSourceCreatedAt()), stamp(e.getSourceUpdatedAt()), employments,
                account, institution != null));
    }

    private static JobOut job(JobEntity j) {
        return new JobOut(j.getIppisJobId(), j.getPositionId(), j.getPositionName(), j.getUnitId(), j.getUnitName(),
                j.getLevel(), j.isHasSubordinates(), j.getEmployeeGroup(), j.isSecondaryEmployment(), j.isActing(),
                date(j.getStartDate()), date(j.getEndDate()), j.getReasonForChange(), stamp(j.getSourceCreatedAt()),
                stamp(j.getSourceUpdatedAt()));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    IdentityStore requireStore() {
        IdentityStore s = store.getIfAvailable();
        if (s == null) {
            throw ApiException.unavailable(ErrorCodes.IDENTITY_DB_NOT_CONFIGURED,
                    "The employee mirror has no database (IDENTITY_DATABASE_URL is not set). "
                            + "See docs/runbooks/create-sso-identity-db.md.");
        }
        return s;
    }

    private static EmployeeRow row(ResultSet rs) throws SQLException {
        return new EmployeeRow(rs.getString("id"), rs.getString("employee_number"), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("email"), rs.getString("phone"), rs.getString("position"),
                rs.getString("status"), rs.getString("institution_code"), rs.getString("institution_slug"),
                rs.getString("keycloak_user_id"),
                Optional.ofNullable(rs.getString("match_state")).orElse(MatchState.UNMATCHED.wire()),
                ts(rs.getTimestamp("source_updated_at")), ts(rs.getTimestamp("first_seen_at")),
                ts(rs.getTimestamp("last_seen_at")), ts(rs.getTimestamp("last_synced_at")),
                rs.getString("last_sync_status"), rs.getString("last_sync_error"));
    }

    private static String whereClause(List<String> c) {
        return c.isEmpty() ? "" : " WHERE " + String.join(" AND ", c);
    }

    static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String ts(Timestamp t) {
        return t == null ? null : OffsetDateTime.ofInstant(t.toInstant(), ZoneOffset.UTC).toString();
    }

    private static String stamp(OffsetDateTime t) {
        return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC).toString();
    }

    private static String date(LocalDate d) {
        return d == null ? null : d.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
