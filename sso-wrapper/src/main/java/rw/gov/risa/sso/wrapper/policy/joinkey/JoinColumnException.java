package rw.gov.risa.sso.wrapper.policy.joinkey;

/** A join column asked for but not storable (write path only; the API maps it to 400). */
public class JoinColumnException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public JoinColumnException(String message) {
        super(message);
    }
}
