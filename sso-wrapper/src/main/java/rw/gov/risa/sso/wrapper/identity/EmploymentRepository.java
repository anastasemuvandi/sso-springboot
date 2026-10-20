package rw.gov.risa.sso.wrapper.identity;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmploymentRepository extends JpaRepository<EmploymentEntity, UUID> {

    List<EmploymentEntity> findByEmployeeId(String employeeId);

    /** The visibility gate of the directory detail: employments at one institution slug. */
    @Query("select e from EmploymentEntity e where e.employeeId = :employeeId and e.institution.shortCode = :slug")
    List<EmploymentEntity> findByEmployeeIdAndInstitutionSlug(@Param("employeeId") String employeeId,
                                                              @Param("slug") String slug);
}
