package rw.gov.risa.sso.wrapper.policy.ippisperson;

import java.time.Instant;
import java.util.Objects;

/**
 * The OLD flat one-record-per-employee contract ({@code schema/ippis.py IppisEmployee}),
 * still delivered by the push webhook. {@code institution} is the raw upstream code, never
 * the slug. A null status means the contract default, {@code active}.
 */
public record IppisEmployeeRecord(
        String employeeNumber,
        String nationalId,
        String firstName,
        String lastName,
        String email,
        String phone,
        String institution,
        String position,
        EmploymentStatus status,
        Instant updatedAt) {

    public IppisEmployeeRecord {
        Objects.requireNonNull(employeeNumber, "employeeNumber");
        status = status == null ? EmploymentStatus.ACTIVE : status;
    }
}
