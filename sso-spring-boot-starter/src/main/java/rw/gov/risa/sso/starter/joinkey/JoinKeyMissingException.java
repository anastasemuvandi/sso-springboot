package rw.gov.risa.sso.starter.joinkey;

/** The token has no usable {@code join_key}; rendered as 403 {@code join_key_missing}. */
public class JoinKeyMissingException extends RuntimeException {
  public static final String ERROR_CODE = "join_key_missing";

  public JoinKeyMissingException() {
    super("The access token carries no join_key claim for this application");
  }
}
