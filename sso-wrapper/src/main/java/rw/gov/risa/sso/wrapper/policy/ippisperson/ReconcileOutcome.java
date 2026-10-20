package rw.gov.risa.sso.wrapper.policy.ippisperson;

/**
 * One per-user outcome. {@code unmappedInstitution} (the unknown code) and
 * {@code ambiguousInstitution} (the employee number) are orthogonal to {@code status}.
 */
public record ReconcileOutcome(
        String userId,
        IppisOutcome status,
        String detail,
        String unmappedInstitution,
        String ambiguousInstitution,
        boolean mirrored,
        String mirrorError) {

    public static ReconcileOutcome of(String userId, IppisOutcome status, String detail) {
        return new ReconcileOutcome(userId, status, detail, null, null, false, null);
    }

    /**
     * Stamp the unmapped/ambiguous flags from the view when not already set — so an employee
     * with no account (who never reached the attribute write) is still reported.
     */
    public ReconcileOutcome withFlagsFrom(PersonView view, InstitutionResolution resolution) {
        String unmapped = unmappedInstitution != null ? unmappedInstitution
                : resolution != null ? resolution.unmappedCode() : null;
        String ambiguous = ambiguousInstitution != null ? ambiguousInstitution
                : view != null && view.isAmbiguous() ? view.employeeNumber() : null;
        return new ReconcileOutcome(userId, status, detail, unmapped, ambiguous, mirrored, mirrorError);
    }

    public ReconcileOutcome withMirror(boolean ok, String error) {
        return new ReconcileOutcome(userId, status, detail, unmappedInstitution, ambiguousInstitution,
                ok, ok ? null : error);
    }
}
