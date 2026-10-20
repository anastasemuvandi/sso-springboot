package rw.gov.risa.sso.wrapper.policy.ippisperson;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * One human with every employment IPPIS holds for them ({@code IppisPerson}). {@code id}
 * <b>is</b> the IPPIS employee number, not a surrogate.
 */
public record IppisPerson(
        String id,
        String nationalId,
        String rssbNumber,
        String phone,
        String email,
        String firstName,
        String lastName,
        String gender,
        String maritalStatus,
        LocalDate dateOfBirth,
        Instant createdAt,
        Instant updatedAt,
        List<IppisEmployment> employments) {

    public IppisPerson {
        Objects.requireNonNull(id, "id");
        employments = employments == null ? List.of() : List.copyOf(employments);
    }

    public static IppisPerson of(String id, IppisEmployment... employments) {
        return new IppisPerson(id, null, null, null, null, null, null, null, null, null, null, null,
                List.of(employments));
    }

    public String employeeNumber() {
        return id;
    }
}
