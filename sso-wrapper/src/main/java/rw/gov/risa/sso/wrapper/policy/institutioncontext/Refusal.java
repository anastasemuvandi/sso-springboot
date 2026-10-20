package rw.gov.risa.sso.wrapper.policy.institutioncontext;

/**
 * Why a delegated request is refused: the HTTP status the API layer must answer, a
 * machine code for the error envelope, and the reference's human message.
 */
public record Refusal(int status, Code code, String message) {

    /** Machine codes (snake_case on the wire, via {@link #wire()}). */
    public enum Code {
        /** 503: the templates are missing or unsafe, so the delegated surface fails closed. */
        DELEGATED_CONFIG_UNAVAILABLE("delegated_config_unavailable"),
        /** 403: caller holds an institution grant AND a broad admin role. */
        GUARDRAIL_VIOLATION("guardrail_violation"),
        /** 403: token institution claim contradicts the grant role. */
        INSTITUTION_CLAIM_MISMATCH("institution_claim_mismatch"),
        /** 403: caller holds no {@code inst:<slug>:*} grant role. */
        NOT_INSTITUTION_ADMIN("not_institution_admin"),
        /** 403: caller named an institution they do not administer. */
        INSTITUTION_NOT_ADMINISTERED("institution_not_administered"),
        /** 400: platform admin with no institution role named none. */
        INSTITUTION_REQUIRED("institution_required"),
        /** 400: caller administers several institutions and named none. */
        MULTIPLE_INSTITUTIONS("multiple_institutions"),
        /** 400: the named institution has no templates (platform admin path). */
        UNKNOWN_INSTITUTION("unknown_institution"),
        /** 403: the templates withhold the capability. */
        CAPABILITY_DENIED("capability_denied"),
        /** 403: role outside the map-roles scope. */
        ROLE_NOT_ASSIGNABLE("role_not_assignable"),
        /** 403: client outside the institution's client scope. */
        CLIENT_OUT_OF_SCOPE("client_out_of_scope");

        private final String wire;

        Code(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        @Override
        public String toString() {
            return wire;
        }
    }

    static Refusal forbidden(Code code, String message) {
        return new Refusal(403, code, message);
    }

    static Refusal badRequest(Code code, String message) {
        return new Refusal(400, code, message);
    }

    /** Throwable form, for callers that prefer exceptions. */
    public InstitutionRefusedException toException() {
        return new InstitutionRefusedException(this);
    }
}
