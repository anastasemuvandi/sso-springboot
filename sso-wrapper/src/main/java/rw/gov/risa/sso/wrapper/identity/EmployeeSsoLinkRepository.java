package rw.gov.risa.sso.wrapper.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeSsoLinkRepository extends JpaRepository<EmployeeSsoLinkEntity, UUID> {
    Optional<EmployeeSsoLinkEntity> findByEmployeeId(String employeeId);
}
