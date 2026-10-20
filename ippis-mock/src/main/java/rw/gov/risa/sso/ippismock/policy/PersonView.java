package rw.gov.risa.sso.ippismock.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import rw.gov.risa.sso.ippismock.store.Employment;
import rw.gov.risa.sso.ippismock.store.Job;

/**
 * Flattening a nested person into one institution / position / status — a copy of the
 * wrapper's person-view rules ({@code app/core/ippis_person_view.py}), used here ONLY to derive the
 * flat {@code /employees} projection of a person stored with real employments. Pure Java.
 *
 * <ol>
 *   <li>The PRIMARY employment (a current job not flagged {@code isSecondaryEmployment}) decides the
 *       institution; ties break on the earliest start date.</li>
 *   <li>Two primaries starting the same day are ambiguous: no institution.</li>
 *   <li>A leaver has no open job ANYWHERE; resigning one of two posts is a move, not an exit.</li>
 * </ol>
 */
public record PersonView(String institution, String position, String status, String ambiguousReason) {

    public static final String ACTIVE = "active";
    public static final String TERMINATED = "terminated";
    public static final String AMBIGUOUS_MULTIPLE_PRIMARY = "multiple_primary_employments";
    public static final String AMBIGUOUS_NO_EMPLOYMENT = "no_employment";

    private record Candidate(Employment employment, List<Job> jobs) {
        String earliestStart() {
            return jobs.stream().map(Job::startDate).min(Comparator.naturalOrder()).orElse("");
        }

        String sortKey() {
            return earliestStart() + "|" + Optional.ofNullable(employment.employerId()).orElse("");
        }
    }

    public static PersonView of(List<Employment> employments) {
        List<Employment> all = employments == null ? List.of() : employments;
        Employment primary = null;
        String ambiguous = null;

        List<Candidate> withCurrent = new ArrayList<>();
        for (Employment e : all) {
            if (!e.currentJobs().isEmpty()) {
                withCurrent.add(new Candidate(e, e.currentJobs()));
            }
        }
        if (withCurrent.isEmpty()) {
            // Leaver: the most recently ended employment, so the record still says where they worked.
            Employment latest = null;
            String latestEnd = null;
            for (Employment e : all) {
                for (Job j : e.jobs()) {
                    if (j.endDate() != null && (latestEnd == null || j.endDate().compareTo(latestEnd) > 0)) {
                        latestEnd = j.endDate();
                        latest = e;
                    }
                }
            }
            primary = latest;
            if (latest == null && !all.isEmpty()) {
                ambiguous = AMBIGUOUS_NO_EMPLOYMENT;
            }
        } else {
            List<Candidate> primaries = withCurrent.stream()
                    .filter(c -> c.jobs().stream().anyMatch(j -> !j.isSecondaryEmployment()))
                    .toList();
            List<Candidate> candidates = primaries.isEmpty() ? withCurrent : primaries;
            if (candidates.size() == 1) {
                primary = candidates.get(0).employment();
            } else {
                List<Candidate> ordered = candidates.stream().sorted(Comparator.comparing(Candidate::sortKey)).toList();
                if (ordered.get(0).earliestStart().equals(ordered.get(1).earliestStart())) {
                    ambiguous = AMBIGUOUS_MULTIPLE_PRIMARY;
                } else {
                    primary = ordered.get(0).employment();
                }
            }
        }

        boolean openAnywhere = all.stream().anyMatch(e -> !e.currentJobs().isEmpty());
        String status = openAnywhere || all.isEmpty() ? ACTIVE : TERMINATED;
        return new PersonView(
                primary == null ? null : primary.employerId(),
                primary == null ? null : currentPosition(primary),
                status,
                ambiguous);
    }

    static String currentPosition(Employment employment) {
        List<Job> pool = employment.currentJobs().isEmpty() ? employment.jobs() : employment.currentJobs();
        return pool.stream()
                .max(Comparator.comparing(Job::startDate).thenComparing(Job::id))
                .map(Job::positionName)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .orElse(null);
    }
}
