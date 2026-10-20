package rw.gov.risa.sso.wrapper.identity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One posting. Append-only: the database trigger allows an UPDATE of {@code end_date} /
 * {@code reason_for_change} only and refuses DELETE. Read-only from JPA's point of view.
 */
@Entity
@Table(name = "job")
@org.hibernate.annotations.Immutable
public class JobEntity {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "ippis_job_id", nullable = false)
    private String ippisJobId;

    @Column(name = "employment_id", nullable = false)
    private UUID employmentId;

    @Column(name = "position_id")
    private String positionId;

    @Column(name = "position_name")
    private String positionName;

    @Column(name = "unit_id")
    private String unitId;

    @Column(name = "unit_name")
    private String unitName;

    @Column(name = "level")
    private String level;

    @Column(name = "has_subordinates")
    private boolean hasSubordinates;

    @Column(name = "employee_group")
    private String employeeGroup;

    @Column(name = "is_secondary_employment")
    private boolean secondaryEmployment;

    @Column(name = "is_acting")
    private boolean acting;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "reason_for_change")
    private String reasonForChange;

    @Column(name = "source_created_at")
    private OffsetDateTime sourceCreatedAt;

    @Column(name = "source_updated_at")
    private OffsetDateTime sourceUpdatedAt;

    protected JobEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getIppisJobId() {
        return ippisJobId;
    }

    public UUID getEmploymentId() {
        return employmentId;
    }

    public String getPositionId() {
        return positionId;
    }

    public String getPositionName() {
        return positionName;
    }

    public String getUnitId() {
        return unitId;
    }

    public String getUnitName() {
        return unitName;
    }

    public String getLevel() {
        return level;
    }

    public boolean isHasSubordinates() {
        return hasSubordinates;
    }

    public String getEmployeeGroup() {
        return employeeGroup;
    }

    public boolean isSecondaryEmployment() {
        return secondaryEmployment;
    }

    public boolean isActing() {
        return acting;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getReasonForChange() {
        return reasonForChange;
    }

    public OffsetDateTime getSourceCreatedAt() {
        return sourceCreatedAt;
    }

    public OffsetDateTime getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }
}
