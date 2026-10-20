package rw.gov.risa.sso.wrapper.policy.fgap;

/** The projection would emit something unsafe ({@code FgapProjectionError}). Never swallowed. */
public class FgapProjectionException extends RuntimeException {

    public FgapProjectionException(String message) {
        super(message);
    }
}
