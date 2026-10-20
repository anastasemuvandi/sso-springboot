package rw.gov.risa.sso.wrapper.service.ippis;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.identity.IdentityStore;
import rw.gov.risa.sso.wrapper.identity.MatchState;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisEmployment;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisJob;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisOutcome;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPerson;
import rw.gov.risa.sso.wrapper.policy.ippisperson.ReconcileOutcome;
import rw.gov.risa.sso.wrapper.policy.registry.InstitutionRegistry;
import rw.gov.risa.sso.wrapper.policy.registry.RegistryEntry;

/**
 * Writes the GESB feed into the normalised mirror (port of {@code db/identity_mirror.py}).
 * One transaction per person, in FK order sector → institution → employee → employment → job →
 * employee_sso_link. The rules it holds:
 * <ol>
 *   <li><b>A reassigned employee number is REFUSED</b> (same number, different national_id), counted
 *       as {@code reassignments}, never written. A stored NULL NID is not "somebody else".</li>
 *   <li><b>An identifier another employee already holds</b> (email case-insensitively, NID, phone) is
 *       refused the same way and counted as {@code conflicts}.</li>
 *   <li><b>{@code job} is append-only</b>: the upsert's update set is {@code end_date},
 *       {@code reason_for_change} and the source stamp — exactly what the DB trigger permits.</li>
 *   <li><b>{@code institution.short_code} / {@code is_onboarded} are INSERT-only.</b></li>
 *   <li><b>Mirror failures are counted, never raised</b> — Keycloak is the work, this is
 *       bookkeeping about the work. Nothing written here is ever read by an authorisation path.</li>
 * </ol>
 */
@Component
public class IdentityMirrorWriter {

    private static final Logger log = LoggerFactory.getLogger(IdentityMirrorWriter.class);

    /** Outcomes after which last_synced_at may advance. */
    static final Set<IppisOutcome> SYNCED = Set.of(IppisOutcome.UPDATED, IppisOutcome.UNCHANGED,
            IppisOutcome.DISABLED, IppisOutcome.ALREADY_DISABLED, IppisOutcome.ACTIVE);
    /** Outcomes whose detail is kept in last_sync_error. */
    static final Set<IppisOutcome> PROBLEMS = Set.of(IppisOutcome.ERROR, IppisOutcome.NO_USER, IppisOutcome.NOT_FOUND);

    static final Map<String, String> IDENTIFIER_CONSTRAINTS = Map.of(
            "uq_employee_email_lower", "email",
            "uq_employee_national_id", "national_id",
            "uq_employee_phone", "phone",
            "uq_employee_rssb_number", "rssb_number");

    private final ObjectProvider<IdentityStore> store;
    private final IppisRegistryProvider registry;
    private final Set<String> warnedEmployers = ConcurrentHashMap.newKeySet();

    public IdentityMirrorWriter(ObjectProvider<IdentityStore> store, IppisRegistryProvider registry) {
        this.store = store;
        this.registry = registry;
    }

    public boolean configured() {
        return store.getIfAvailable() != null;
    }

    /** A recorder for one run, or empty when IDENTITY_DATABASE_URL is unset (a supported state). */
    public Optional<Recorder> recorder() {
        IdentityStore s = store.getIfAvailable();
        return s == null ? Optional.empty() : Optional.of(new Recorder(s));
    }

    /** {@code max(employee.source_updated_at)} — IPPIS's clock, never our row mtime. */
    public Optional<Instant> sourceWatermark() {
        IdentityStore s = store.getIfAvailable();
        if (s == null) {
            return Optional.empty();
        }
        Timestamp t = s.jdbc().getJdbcTemplate().queryForObject("SELECT max(source_updated_at) FROM employee",
                Timestamp.class);
        return Optional.ofNullable(t).map(Timestamp::toInstant);
    }

    /** Refusal: the employee number now names a different person. */
    public static final class EmployeeNumberReassigned extends RuntimeException {
        public EmployeeNumberReassigned(String number, String stored, String incoming) {
            super("employee number " + number + " is stored against national_id '" + stored
                    + "' but IPPIS now reports '" + incoming + "' — refusing to overwrite, because the employment and "
                    + "job history filed under this number would silently become the new person's");
        }
    }

    /** Refusal: a unique identifier IPPIS sent for this person is already another's. */
    public static final class IdentifierHeldByAnother extends RuntimeException {
        public IdentifierHeldByAnother(String number, String field, String value, String holder) {
            super("employee number " + number + " reports " + (value != null ? field + " '" + value + "'" : field)
                    + ", which " + (holder != null ? "employee number " + holder + " already holds"
                    : "another employee already holds") + " — refusing to write, because one " + field
                    + " cannot identify two people and the SSO cannot tell whose it is");
        }
    }

    /** Per-run counters; thread-safe (the reconcile records concurrently). */
    public final class Recorder {
        private final IdentityStore s;
        private final AtomicInteger recorded = new AtomicInteger();
        private final AtomicInteger errors = new AtomicInteger();
        private final AtomicInteger reassignments = new AtomicInteger();
        private final AtomicInteger conflicts = new AtomicInteger();
        private final AtomicInteger jobsInserted = new AtomicInteger();
        private final AtomicInteger jobsClosed = new AtomicInteger();
        private final List<String> refused = Collections.synchronizedList(new ArrayList<>());
        private final List<String> conflicted = Collections.synchronizedList(new ArrayList<>());
        private final Set<String> unmappedEmployers = ConcurrentHashMap.newKeySet();
        private volatile String firstError;

        Recorder(IdentityStore s) {
            this.s = s;
        }

        /** Upsert one person; null on success, else the refusal/error message. Never throws. */
        public String record(IppisPerson person, ReconcileOutcome outcome, String keycloakUserId, MatchState state) {
            int[] jobs = new int[2];
            List<String> unmapped = new ArrayList<>();
            try {
                s.tx().executeWithoutResult(t -> upsertPerson(s.jdbc(), person, outcome, keycloakUserId, state, jobs,
                        unmapped));
            } catch (EmployeeNumberReassigned e) {
                reassignments.incrementAndGet();
                refused.add(person.id());
                log.warn("ippis mirror: {}", e.getMessage());
                return e.getMessage();
            } catch (IdentifierHeldByAnother e) {
                conflicts.incrementAndGet();
                conflicted.add(person.id());
                log.warn("ippis mirror: {}", e.getMessage());
                return e.getMessage();
            } catch (DataIntegrityViolationException e) {
                String field = conflictingIdentifier(e);
                if (field == null) {
                    return countError(person, e);
                }
                conflicts.incrementAndGet();
                conflicted.add(person.id());
                String msg = new IdentifierHeldByAnother(person.id(), field, null, null).getMessage();
                log.warn("ippis mirror: {}", msg);
                return msg;
            } catch (RuntimeException e) {
                return countError(person, e);
            }
            recorded.incrementAndGet();
            jobsInserted.addAndGet(jobs[0]);
            jobsClosed.addAndGet(jobs[1]);
            unmappedEmployers.addAll(unmapped);
            return null;
        }

        private String countError(IppisPerson person, Exception e) {
            errors.incrementAndGet();
            String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (firstError == null) {
                firstError = detail;
                log.warn("ippis mirror: write failed for {} ({}); the sync continues and further mirror failures in "
                        + "this run are logged at debug", person.id(), detail);
            } else {
                log.debug("ippis mirror: write failed for {}: {}", person.id(), detail);
            }
            return detail;
        }

        /** Opens one connection and runs SELECT 1 (the {@code --require-mirror} precondition). Throws on failure. */
        public void probe() {
            s.jdbc().getJdbcTemplate().queryForObject("SELECT 1", Integer.class);
        }

        public int recorded() {
            return recorded.get();
        }

        public int errors() {
            return errors.get();
        }

        public int reassignments() {
            return reassignments.get();
        }

        public int conflicts() {
            return conflicts.get();
        }

        public Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recorded", recorded.get());
            m.put("errors", errors.get());
            m.put("reassignments", reassignments.get());
            m.put("conflicts", conflicts.get());
            m.put("conflicted", List.copyOf(conflicted));
            m.put("refused", List.copyOf(refused));
            m.put("jobs_inserted", jobsInserted.get());
            m.put("jobs_closed", jobsClosed.get());
            m.put("unmapped_employers", List.copyOf(new TreeSet<>(unmappedEmployers)));
            m.put("first_error", firstError);
            return m;
        }
    }

    // ---- the per-person write --------------------------------------------------------------------

    void upsertPerson(NamedParameterJdbcTemplate jdbc, IppisPerson person, ReconcileOutcome outcome,
                      String keycloakUserId, MatchState state, int[] jobs, List<String> unmapped) {
        String number = person.id().strip();
        assertNotReassigned(jdbc, number, blankToNull(person.nationalId()));
        assertIdentifiersFree(jdbc, number, blankToNull(person.email()), blankToNull(person.nationalId()),
                blankToNull(person.phone()));

        jdbc.update("""
                INSERT INTO employee (id, national_id, rssb_number, phone, email, first_name, last_name, gender,
                                      marital_status, date_of_birth, source_created_at, source_updated_at)
                VALUES (:id, :nid, :rssb, :phone, :email, :first, :last, :gender, :marital, :dob, :created, :updated)
                ON CONFLICT (id) DO UPDATE SET
                    national_id = EXCLUDED.national_id, rssb_number = EXCLUDED.rssb_number,
                    phone = EXCLUDED.phone, email = EXCLUDED.email, first_name = EXCLUDED.first_name,
                    last_name = EXCLUDED.last_name, gender = EXCLUDED.gender,
                    marital_status = EXCLUDED.marital_status, date_of_birth = EXCLUDED.date_of_birth,
                    source_created_at = EXCLUDED.source_created_at, source_updated_at = EXCLUDED.source_updated_at,
                    updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", number)
                .addValue("nid", blankToNull(person.nationalId()))
                .addValue("rssb", blankToNull(person.rssbNumber()))
                .addValue("phone", blankToNull(person.phone()))
                .addValue("email", blankToNull(person.email()))
                .addValue("first", blankToNull(person.firstName()))
                .addValue("last", blankToNull(person.lastName()))
                .addValue("gender", IppisJson.normalizeEnum(person.gender(), IppisJson.GENDERS))
                .addValue("marital", IppisJson.normalizeEnum(person.maritalStatus(), IppisJson.MARITAL_STATUSES))
                .addValue("dob", person.dateOfBirth())
                .addValue("created", ts(person.createdAt()))
                .addValue("updated", ts(person.updatedAt())));

        for (IppisEmployment employment : person.employments()) {
            String employer = employment.employerId() == null ? "" : employment.employerId().strip();
            if (employer.isEmpty()) {
                log.debug("ippis mirror: {} has an employment with no employerId; skipped", number);
                continue;
            }
            String sector = blankToNull(employment.sectorId());
            if (sector != null) {
                jdbc.update("""
                        INSERT INTO sector (id, name) VALUES (:id, :name)
                        ON CONFLICT (id) DO UPDATE SET name = COALESCE(EXCLUDED.name, sector.name), updated_at = now()
                        """, new MapSqlParameterSource("id", sector)
                        .addValue("name", Optional.ofNullable(blankToNull(employment.sectorName())).orElse(sector)));
            }
            String slug = resolveShortCode(employer, employment.employerName());
            if (slug == null) {
                unmapped.add(employer);
            }
            boolean onboarded = slug != null && registry.registry().entryFor(slug).map(RegistryEntry::isActive)
                    .orElse(false);
            UUID institutionId = jdbc.queryForObject("""
                    INSERT INTO institution (ippis_institution_id, name, sector_id, source_updated_at, short_code,
                                             is_onboarded)
                    VALUES (:code, :name, :sector, :updated, :slug, :onboarded)
                    ON CONFLICT ON CONSTRAINT uq_institution_ippis_institution_id DO UPDATE SET
                        name = EXCLUDED.name, sector_id = EXCLUDED.sector_id,
                        source_updated_at = EXCLUDED.source_updated_at, updated_at = now()
                    RETURNING id
                    """, new MapSqlParameterSource("code", employer)
                    .addValue("name", Optional.ofNullable(blankToNull(employment.employerName())).orElse(employer))
                    .addValue("sector", sector)
                    .addValue("updated", ts(employment.sourceUpdatedAt().orElse(null)))
                    .addValue("slug", slug)
                    .addValue("onboarded", onboarded), UUID.class);
            UUID employmentId = jdbc.queryForObject("""
                    INSERT INTO employment (employee_id, institution_id, source_updated_at)
                    VALUES (:employee, :institution, :updated)
                    ON CONFLICT ON CONSTRAINT uq_employment_employee_institution DO UPDATE SET
                        source_updated_at = EXCLUDED.source_updated_at, updated_at = now()
                    RETURNING id
                    """, new MapSqlParameterSource("employee", number).addValue("institution", institutionId)
                    .addValue("updated", ts(employment.sourceUpdatedAt().orElse(null))), UUID.class);
            for (IppisJob job : employment.jobs()) {
                List<Boolean> inserted = jdbc.queryForList("""
                        INSERT INTO job (ippis_job_id, employment_id, position_id, position_name, unit_id, unit_name,
                                         level, has_subordinates, employee_group, is_secondary_employment, is_acting,
                                         start_date, end_date, reason_for_change, source_created_at,
                                         source_updated_at)
                        VALUES (:jid, :employment, :pid, :pname, :uid, :uname, :level, :subs, :grp, :secondary,
                                :acting, :start, :end, :reason, :created, :updated)
                        ON CONFLICT ON CONSTRAINT uq_job_ippis_job_id DO UPDATE SET
                            end_date = EXCLUDED.end_date, reason_for_change = EXCLUDED.reason_for_change,
                            source_updated_at = EXCLUDED.source_updated_at
                        WHERE job.end_date IS DISTINCT FROM EXCLUDED.end_date
                           OR job.reason_for_change IS DISTINCT FROM EXCLUDED.reason_for_change
                        RETURNING (xmax = 0) AS inserted
                        """, new MapSqlParameterSource("jid", job.id().strip())
                        .addValue("employment", employmentId)
                        .addValue("pid", blankToNull(job.positionId()))
                        .addValue("pname", blankToNull(job.positionName()))
                        .addValue("uid", blankToNull(job.unitId()))
                        .addValue("uname", blankToNull(job.unitName()))
                        .addValue("level", blankToNull(job.level()))
                        .addValue("subs", job.hasSubordinates())
                        .addValue("grp", IppisJson.normalizeEnum(job.employeeGroup(), IppisJson.EMPLOYEE_GROUPS))
                        .addValue("secondary", job.secondaryEmployment())
                        .addValue("acting", job.acting())
                        .addValue("start", job.startDate())
                        .addValue("end", job.endDate())
                        .addValue("reason", IppisJson.normalizeEnum(job.reasonForChange(), IppisJson.REASONS_FOR_CHANGE))
                        .addValue("created", ts(job.createdAt()))
                        .addValue("updated", ts(job.updatedAt())), Boolean.class);
                if (!inserted.isEmpty()) {
                    jobs[Boolean.TRUE.equals(inserted.get(0)) ? 0 : 1]++;
                }
            }
        }
        upsertLink(jdbc, number, outcome, keycloakUserId, state);
    }

    private static void upsertLink(NamedParameterJdbcTemplate jdbc, String number, ReconcileOutcome outcome,
                                   String keycloakUserId, MatchState state) {
        String status = outcome == null ? "recorded" : outcome.status().wire();
        String error = outcome != null && PROBLEMS.contains(outcome.status()) && outcome.detail() != null
                ? outcome.detail() : null;
        boolean synced = outcome != null && SYNCED.contains(outcome.status());
        // match_state null = "we did not determine it" (the Keycloak lookup itself failed): the match
        // columns are left alone so a blip cannot push a matched employee into the joiner queue.
        StringBuilder cols = new StringBuilder("employee_id, last_seen_at, last_sync_status, last_sync_error");
        StringBuilder vals = new StringBuilder(":employee, now(), :status, :error");
        StringBuilder set = new StringBuilder("last_seen_at = now(), last_sync_status = EXCLUDED.last_sync_status, "
                + "last_sync_error = EXCLUDED.last_sync_error, updated_at = now()");
        if (state != null) {
            cols.append(", keycloak_user_id, match_state");
            vals.append(", :kc, :state");
            set.append(", keycloak_user_id = EXCLUDED.keycloak_user_id, match_state = EXCLUDED.match_state");
        }
        if (synced) {
            cols.append(", last_synced_at");
            vals.append(", now()");
            set.append(", last_synced_at = now()");
        }
        jdbc.update("INSERT INTO employee_sso_link (" + cols + ") VALUES (" + vals + ") "
                        + "ON CONFLICT ON CONSTRAINT uq_employee_sso_link_employee DO UPDATE SET " + set,
                new MapSqlParameterSource("employee", number).addValue("status", status).addValue("error", error)
                        .addValue("kc", keycloakUserId).addValue("state", state == null ? null : state.wire()));
    }

    static void assertNotReassigned(NamedParameterJdbcTemplate jdbc, String number, String incomingNid) {
        if (incomingNid == null) {
            return;
        }
        List<String> stored = jdbc.queryForList("SELECT national_id FROM employee WHERE id = :id",
                Map.of("id", number), String.class);
        if (stored.isEmpty() || stored.get(0) == null) {
            return;
        }
        String s = stored.get(0);
        if (!s.strip().toLowerCase(Locale.ROOT).equals(incomingNid.strip().toLowerCase(Locale.ROOT))) {
            throw new EmployeeNumberReassigned(number, s, incomingNid);
        }
    }

    static void assertIdentifiersFree(NamedParameterJdbcTemplate jdbc, String number, String email, String nid,
                                      String phone) {
        if (email == null && nid == null && phone == null) {
            return;
        }
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource p = new MapSqlParameterSource("id", number);
        if (email != null) {
            clauses.add("lower(email) = :email");
            p.addValue("email", email.strip().toLowerCase(Locale.ROOT));
        }
        if (nid != null) {
            clauses.add("national_id = :nid");
            p.addValue("nid", nid.strip());
        }
        if (phone != null) {
            clauses.add("phone = :phone");
            p.addValue("phone", phone.strip());
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, email, national_id, phone FROM employee "
                + "WHERE id <> :id AND (" + String.join(" OR ", clauses) + ")", p);
        for (Map<String, Object> r : rows) {
            String holder = (String) r.get("id");
            String he = (String) r.get("email");
            String hn = (String) r.get("national_id");
            String hp = (String) r.get("phone");
            if (email != null && he != null && he.strip().equalsIgnoreCase(email.strip())) {
                throw new IdentifierHeldByAnother(number, "email", email, holder);
            }
            if (nid != null && hn != null && hn.strip().equals(nid.strip())) {
                throw new IdentifierHeldByAnother(number, "national_id", nid, holder);
            }
            if (phone != null && hp != null && hp.strip().equals(phone.strip())) {
                throw new IdentifierHeldByAnother(number, "phone", phone, holder);
            }
        }
    }

    /** The registry slug for an employer id, or null (never guessed). Warns once per employer. */
    String resolveShortCode(String employerId, String employerName) {
        InstitutionRegistry r = registry.registry();
        Optional<String> slug = r.slugForIppisCode(employerId);
        if (slug.isPresent()) {
            return slug.get();
        }
        if (warnedEmployers.add(employerId)) {
            log.warn("ippis mirror: employer '{}' ('{}') is not in the registry (ippis_codes) — its employees are "
                    + "recorded but belong to no institution and are visible only to a platform admin", employerId,
                    employerName);
        }
        return null;
    }

    static String conflictingIdentifier(Throwable e) {
        String text = String.valueOf(e.getMessage()) + " " + (e.getCause() == null ? "" : e.getCause().getMessage());
        for (Map.Entry<String, String> c : IDENTIFIER_CONSTRAINTS.entrySet()) {
            if (text.contains(c.getKey())) {
                return c.getValue();
            }
        }
        return null;
    }

    static String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.strip();
        return t.isEmpty() ? null : t;
    }

    private static java.time.OffsetDateTime ts(Instant i) {
        return i == null ? null : java.time.OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC);
    }
}
