package rw.gov.risa.sso.wrapper.policy.clientscope;

import java.util.Optional;

/**
 * Who may create or edit what on a client, from a <b>delegated</b> (institution) admin's side.
 *
 * <ul>
 *   <li>A delegated admin creates apps with the app part only: the client-id is composed as
 *       {@code <their slug>-<app>} ({@link ClientScopes#clientIdFor}); no input names another
 *       institution, and no input can express a cross-cutting client.</li>
 *   <li>Only a platform admin creates cross-cutting clients or edits a scope
 *       ({@link ClientScope#requested}, {@link ScopeChangePolicy}).</li>
 *   <li>On a cross-cutting client a delegated admin may not change the join column, the MFA
 *       requirement, or read the secret (403) — {@link #crossCuttingRefusal}.</li>
 * </ul>
 */
public final class DelegatedAppRules {

    private DelegatedAppRules() {
    }

    /** Operations a delegated admin may attempt on an app that are platform-only on a cross-cutting one. */
    public enum Operation {
        JOIN_COLUMN,
        MFA,
        SECRET
    }

    /**
     * The 403 message when {@code scope} is cross-cutting and a delegated admin attempts
     * {@code operation}; empty otherwise (the caller still runs the institution scope check).
     */
    public static Optional<String> crossCuttingRefusal(ClientScope scope, Operation operation) {
        if (!scope.crossCutting()) {
            return Optional.empty();
        }
        String id = ClientScopes.repr(scope.clientId());
        return Optional.of(switch (operation) {
            case JOIN_COLUMN -> "Client " + id + " is cross-cutting: several institutions use it, so which "
                    + "identifier it authenticates users by is a platform admin's decision, not one "
                    + "institution's.";
            case MFA -> "Client " + id + " is cross-cutting: several institutions use it, so whether it "
                    + "requires TOTP is a platform admin's decision.";
            case SECRET -> "Client " + id + " is cross-cutting: its secret belongs to the platform, not to "
                    + "one institution. A platform admin issues it to whoever operates the application.";
        });
    }
}
