package rw.gov.risa.sso.wrapper.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Employee ↔ institution; natural key (employee_id, institution_id). */
@Entity
@Table(name = "employment")
public class EmploymentEntity {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private String employeeId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "institution_id", nullable = false)
    private InstitutionEntity institution;

    @Column(name = "source_updated_at")
    private OffsetDateTime sourceUpdatedAt;

    protected EmploymentEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public InstitutionEntity getInstitution() {
        return institution;
    }

    public OffsetDateTime getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }
}
