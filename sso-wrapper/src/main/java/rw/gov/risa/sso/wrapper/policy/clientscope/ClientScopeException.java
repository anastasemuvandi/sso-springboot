package rw.gov.risa.sso.wrapper.policy.clientscope;

/**
 * A scope (or composed client-id) that was asked for but may not be stored.
 *
 * <p>Raised only by the write path ({@link ClientScope#requested} and
 * {@link ClientScopes#clientIdFor}). The API layer turns it into a 400; nothing in a read
 * path raises it.
 */
public class ClientScopeException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public ClientScopeException(String message) {
        super(message);
    }
}
