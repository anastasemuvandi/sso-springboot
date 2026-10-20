package rw.gov.risa.sso.wrapper.identity;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<JobEntity, UUID> {

    /** Current first, then most recent start, then IPPIS id (deterministic). */
    @Query("select j from JobEntity j where j.employmentId = :employmentId order by "
            + "case when j.endDate is null then 0 else 1 end, j.startDate desc, j.ippisJobId desc")
    List<JobEntity> history(@Param("employmentId") UUID employmentId);
}
