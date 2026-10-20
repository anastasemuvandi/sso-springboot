package rw.gov.risa.sso.wrapper.policy.institutioncontext;

/** Carries a {@link Refusal}; the API layer maps it to the error envelope. */
public class InstitutionRefusedException extends RuntimeException {

    private final transient Refusal refusal;

    public InstitutionRefusedException(Refusal refusal) {
        super(refusal.message());
        this.refusal = refusal;
    }

    public Refusal refusal() {
        return refusal;
    }
}
