package rw.gov.risa.sso.wrapper.policy.ippisperson;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Flattening a nested IPPIS person into the facts Keycloak can hold (port of
 * {@code ippis_person_view.py}).
 *
 * <ol>
 *   <li>The PRIMARY employment decides the institution: one holding a current job not flagged
 *       {@code isSecondaryEmployment}; all-secondary people treat every current employment as
 *       a candidate. Ties break on the earliest start date (employer id as a deterministic
 *       tiebreak for ordering only).</li>
 *   <li>Two candidates starting the same day → institution left UNWRITTEN,
 *       {@code multiple_primary_employments}.</li>
 *   <li>A leaver has no open job ANYWHERE. A person with no employments at all is never a
 *       leaver (unreadable record, not evidence of termination).</li>
 * </ol>
 */
public final class IppisPersonViews {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_TERMINATED = "terminated";
    public static final String AMBIGUOUS_MULTIPLE_PRIMARY = "multiple_primary_employments";
    public static final String AMBIGUOUS_NO_EMPLOYMENT = "no_employment";

    private IppisPersonViews() {
    }

    private record Candidate(IppisEmployment employment, List<IppisJob> jobs) {
        LocalDate earliestStart() {
            return jobs.stream().map(IppisJob::startDate).min(Comparator.naturalOrder()).orElseThrow();
        }

        String employer() {
            return employment.employerId() == null ? "" : employment.employerId();
        }
    }

    /** Rules 1 and 2. A leaver still gets an employment back — the most recently ended one. */
    public static PrimaryEmployment primaryEmployment(IppisPerson person) {
        List<Candidate> withCurrent = new ArrayList<>();
        for (IppisEmployment e : person.employments()) {
            List<IppisJob> current = e.currentJobs();
            if (!current.isEmpty()) {
                withCurrent.add(new Candidate(e, current));
            }
        }

        if (withCurrent.isEmpty()) {
            List<Candidate> ended = new ArrayList<>();
            for (IppisEmployment e : person.employments()) {
                if (!e.jobs().isEmpty() && e.jobs().stream().anyMatch(j -> j.endDate() != null)) {
                    ended.add(new Candidate(e, e.jobs()));
                }
            }
            if (ended.isEmpty()) {
                return new PrimaryEmployment(null,
                        person.employments().isEmpty() ? null : AMBIGUOUS_NO_EMPLOYMENT);
            }
            Candidate latest = null;
            LocalDate latestEnd = null;
            for (Candidate c : ended) {
                LocalDate end = c.jobs().stream().map(IppisJob::endDate)
                        .filter(d -> d != null).max(Comparator.naturalOrder()).orElse(LocalDate.MIN);
                if (latest == null || end.isAfter(latestEnd)) {
                    latest = c;
                    latestEnd = end;
                }
            }
            return new PrimaryEmployment(latest.employment(), null);
        }

        List<Candidate> primary = withCurrent.stream()
                .filter(c -> c.jobs().stream().anyMatch(j -> !j.secondaryEmployment()))
                .toList();
        List<Candidate> candidates = primary.isEmpty() ? withCurrent : primary;
        if (candidates.size() == 1) {
            return new PrimaryEmployment(candidates.get(0).employment(), null);
        }
        List<Candidate> ordered = candidates.stream()
                .sorted(Comparator.comparing(Candidate::earliestStart).thenComparing(Candidate::employer))
                .toList();
        if (ordered.get(0).earliestStart().equals(ordered.get(1).earliestStart())) {
            return new PrimaryEmployment(null, AMBIGUOUS_MULTIPLE_PRIMARY);
        }
        return new PrimaryEmployment(ordered.get(0).employment(), null);
    }

    /** The job title to publish: the latest current posting, else the last one held. */
    public static String currentPosition(IppisEmployment employment) {
        List<IppisJob> pool = employment.currentJobs().isEmpty() ? employment.jobs() : employment.currentJobs();
        if (pool.isEmpty()) {
            return null;
        }
        IppisJob latest = null;
        for (IppisJob j : pool) {
            if (latest == null || j.startDate().isAfter(latest.startDate())
                    || (j.startDate().equals(latest.startDate()) && j.id().compareTo(latest.id()) > 0)) {
                latest = j;
            }
        }
        return strip(latest.positionName());
    }

    /** Flatten one person. Total: every input produces a view, never an error. */
    public static PersonView viewOf(IppisPerson person) {
        PrimaryEmployment pe = primaryEmployment(person);
        IppisEmployment employment = pe.employment();

        boolean openJobAnywhere = person.employments().stream().anyMatch(e -> !e.currentJobs().isEmpty());
        String status = openJobAnywhere ? STATUS_ACTIVE : STATUS_TERMINATED;
        if (person.employments().isEmpty()) {
            status = STATUS_ACTIVE;
        }

        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (employment != null) {
            codes.add(employment.employerId());
        }
        for (IppisEmployment e : person.employments()) {
            if (employment == null || !e.employerId().equals(employment.employerId())) {
                codes.add(e.employerId());
            }
        }

        return new PersonView(
                person.id().strip(),
                strip(person.nationalId()),
                strip(person.phone()),
                strip(person.email()),
                strip(person.firstName()),
                strip(person.lastName()),
                employment != null ? employment.employerId() : null,
                employment != null ? currentPosition(employment) : null,
                status,
                pe.ambiguousReason(),
                new ArrayList<>(codes));
    }

    /**
     * The same flat view from the OLD flat contract (push webhook). Never ambiguous; only
     * {@code terminated} is a leaver ({@code suspended} is not).
     */
    public static PersonView viewOfEmployee(IppisEmployeeRecord emp) {
        String code = strip(emp.institution());
        return new PersonView(
                emp.employeeNumber(),
                strip(emp.nationalId()),
                strip(emp.phone()),
                strip(emp.email()),
                strip(emp.firstName()),
                strip(emp.lastName()),
                code,
                strip(emp.position()),
                emp.status() == EmploymentStatus.TERMINATED ? STATUS_TERMINATED : STATUS_ACTIVE,
                null,
                code != null ? List.of(code) : List.of());
    }

    static String strip(String value) {
        if (value == null) {
            return null;
        }
        String t = value.strip();
        return t.isEmpty() ? null : t;
    }
}
