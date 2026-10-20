package rw.gov.risa.sso.wrapper.identity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<EmployeeEntity, String> {
}
