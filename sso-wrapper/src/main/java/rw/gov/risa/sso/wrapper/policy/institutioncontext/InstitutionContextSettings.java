package rw.gov.risa.sso.wrapper.policy.institutioncontext;

/**
 * {@code DELEGATED_GUARDRAIL_ENFORCE} and {@code DELEGATED_CLAIM_CROSSCHECK}; both default to
 * {@code true}. When off, the violation/mismatch is still reported as a warning.
 */
public record InstitutionContextSettings(boolean guardrailEnforce, boolean claimCrosscheck) {

    public static InstitutionContextSettings defaults() {
        return new InstitutionContextSettings(true, true);
    }
}
