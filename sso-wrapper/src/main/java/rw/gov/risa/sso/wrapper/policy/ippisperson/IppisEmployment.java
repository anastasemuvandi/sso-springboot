package rw.gov.risa.sso.wrapper.policy.ippisperson;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One employer and the jobs held there ({@code schema/ippis_identity.py IppisEmployment}). */
public record IppisEmployment(
        String employerId,
        String employerName,
        String sectorId,
        String sectorName,
        List<IppisJob> jobs) {

    public IppisEmployment {
        Objects.requireNonNull(employerId, "employerId");
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }

    public static IppisEmployment of(String employerId, IppisJob... jobs) {
        return new IppisEmployment(employerId, employerId, null, null, List.of(jobs));
    }

    public List<IppisJob> currentJobs() {
        return jobs.stream().filter(IppisJob::isCurrent).toList();
    }

    /** The newest {@code updatedAt} among the jobs (the feed stamps nothing at this level). */
    public Optional<Instant> sourceUpdatedAt() {
        return jobs.stream().map(IppisJob::updatedAt).filter(Objects::nonNull).max(Instant::compareTo);
    }
}
