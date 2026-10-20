package rw.gov.risa.sso.mfa;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.sessions.AuthenticationSessionModel;
import rw.gov.risa.sso.mfa.policy.MfaConstants;
import rw.gov.risa.sso.mfa.policy.MfaPolicy;

/**
 * First step of {@code browser-totp}: if TOTP applies to the client being
 * logged in to, ask for level of authentication 2.
 *
 * <p>Why a separate step and not only the 2FA condition: SSO. With per-client
 * 2FA, a person can hold a session from a client that did not require a code
 * (level 1) and then open one that does. Keycloak's cookie authenticator lets
 * that session through unless the REQUESTED level is above the session's — and
 * no client here sends {@code acr_values}. Raising the requested level here is
 * what makes the cookie step up to the OTP page instead of handing the second
 * client a level-1 session. (Keycloak's own per-client
 * {@code minimum.acr.value} would do the same, but would have to be rewritten on
 * every client whenever the realm mode changed.)
 *
 * <p>An exempt person is never raised: their 2FA sub-flow is skipped, so a
 * raised request would leave the SSO path with nothing that can satisfy it. The
 * user is not known yet on a fresh login, so the identity cookie is peeked —
 * without it there is no session to step up from, and a fresh login's password
 * step identifies them before the (skipped) 2FA sub-flow anyway.
 */
public class StepUpAuthenticator implements Authenticator {

    private static final Logger log = Logger.getLogger(StepUpAuthenticator.class);

    static final StepUpAuthenticator SINGLETON = new StepUpAuthenticator();
    static final int LEVEL = MfaConstants.LOA_TOTP;

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        RealmModel realm = context.getRealm();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        ClientModel client = authSession.getClient();

        boolean applies = MfaPolicy.applies(
                realm.getAttribute(MfaConstants.MODE_ATTRIBUTE),
                realm.getAttribute(MfaConstants.LEGACY_ATTRIBUTE),
                client == null ? null : client.getAttribute(MfaConstants.CLIENT_ATTRIBUTE));

        if (applies && !cookieUserIsExempt(context)) {
            String raised = raisedLevel(authSession.getClientNote(Constants.REQUESTED_LEVEL_OF_AUTHENTICATION));
            if (raised != null) {
                authSession.setClientNote(Constants.REQUESTED_LEVEL_OF_AUTHENTICATION, raised);
            }
        }
        log.debugf("client '%s': TOTP %s", client == null ? null : client.getClientId(), applies ? "applies" : "does not apply");
        context.success();
    }

    private static boolean cookieUserIsExempt(AuthenticationFlowContext context) {
        try {
            AuthenticationManager.AuthResult result =
                    AuthenticationManager.authenticateIdentityCookie(context.getSession(), context.getRealm(), false);
            if (result == null) {
                return false;
            }
            UserModel user = result.getUser();
            RoleModel exempt = context.getRealm().getRole(MfaConstants.EXEMPT_ROLE);
            return user != null && exempt != null && user.hasRole(exempt);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The requested-level client note to write, or null to leave it: raised to
     * {@link #LEVEL} when absent, unparsable or lower; never lowered.
     */
    static String raisedLevel(String currentNote) {
        int requested = MfaPolicy.parseLevel(currentNote).orElse(Constants.NO_LOA);
        return requested < LEVEL ? String.valueOf(LEVEL) : null;
    }

    @Override
    public void action(AuthenticationFlowContext context) {
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
}
