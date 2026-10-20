package rw.gov.risa.sso.ippismock.store;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One employer and the jobs held there (feed shape). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Employment(String employerId, String employerName, String sectorId, String sectorName, List<Job> jobs) {

    public Employment {
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }

    public List<Job> currentJobs() {
        return jobs.stream().filter(Job::isCurrent).toList();
    }
}
