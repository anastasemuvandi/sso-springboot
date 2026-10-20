package rw.gov.risa.sso.wrapper.policy.ippisperson;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One posting (GESB contract, {@code schema/ippis_identity.py IppisJob}). {@code endDate}
 * null means it is the employee's current job. Field names here are the Java form of the
 * feed's camelCase ({@code positionName}, {@code isSecondaryEmployment}, ...).
 */
public record IppisJob(
        String id,
        String positionId,
        String positionName,
        String unitId,
        String unitName,
        String level,
        boolean hasSubordinates,
        String employeeGroup,
        boolean secondaryEmployment,
        boolean acting,
        LocalDate startDate,
        LocalDate endDate,
        String reasonForChange,
        Instant createdAt,
        Instant updatedAt) {

    public IppisJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(startDate, "startDate");
    }

    /** Convenience for the fields the person-view policy reads. */
    public static IppisJob of(String id, String positionName, LocalDate startDate, LocalDate endDate,
                              boolean secondaryEmployment) {
        return new IppisJob(id, null, positionName, null, null, null, false, null,
                secondaryEmployment, false, startDate, endDate, null, null, null);
    }

    public boolean isCurrent() {
        return endDate == null;
    }
}
