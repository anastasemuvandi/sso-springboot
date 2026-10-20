package rw.gov.risa.sso.wrapper.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InstitutionRepository extends JpaRepository<InstitutionEntity, UUID> {
    Optional<InstitutionEntity> findByIppisInstitutionId(String ippisInstitutionId);
}
