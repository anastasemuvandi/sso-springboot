package rw.gov.risa.sso.wrapper.policy.delegated;

/**
 * The template file is missing, malformed, or unsafe ({@code DelegatedPermissionConfigError}).
 *
 * <p>Raised at load time and never swallowed: the delegated-admin surface fails closed
 * (503) rather than falling back to some implicit default nobody reviewed. Also raised by
 * {@link DelegatedPermissionRegistry#grantFor(String)} for an invalid slug or an
 * institution with every tier withheld.
 */
public class DelegatedPermissionConfigException extends RuntimeException {

    public DelegatedPermissionConfigException(String message) {
        super(message);
    }

    public DelegatedPermissionConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
