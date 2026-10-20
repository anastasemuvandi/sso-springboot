package rw.gov.risa.sso.wrapper.policy.accessmatrix;

/**
 * One row of the institution's roster as the IPPIS mirror reports it
 * ({@code employee_directory.institution_roster}). {@code status} is per institution:
 * {@code terminated} = no open post HERE. {@code keycloakUserId} is the mirror's link, when one is
 * recorded. Any field except {@code employeeNumber} may be null.
 */
public record RosterEmployee(
        String employeeNumber,
        String firstName,
        String lastName,
        String email,
        String nationalId,
        String status,
        String gender,
        String keycloakUserId,
        String institution) {

    public RosterEmployee {
        if (employeeNumber == null) {
            throw new IllegalArgumentException("employeeNumber is required");
        }
    }

    public boolean isLeaver() {
        return AccessMatrix.STATUS_TERMINATED.equals(status);
    }
}
