package rw.gov.risa.sso.mfa;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowCallback;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.credential.CredentialModel;
import org.keycloak.events.Details;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.OTPCredentialModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import rw.gov.risa.sso.mfa.policy.MfaConstants;

import java.util.List;
import java.util.Map;

/**
 * Runs right after the recovery-code form: the person has told us they cannot
 * produce a TOTP code, so their authenticator is presumed lost.
 *
 * <ol>
 *   <li>Requires CONFIGURE_TOTP on the user, so the login does not complete
 *       until a new device is enrolled. Set on the USER, not the auth session,
 *       so an abandoned enrolment is demanded again at the next login.</li>
 *   <li>Removes their existing OTP credential(s) — a lost phone still holds a
 *       working secret, and leaving it valid is the one outcome a recovery path
 *       must not have. Configurable ({@code remove_existing}).</li>
 * </ol>
 *
 * <p>The removal happens in {@link #onTopFlowSuccess}, AFTER the flow is done,
 * never in {@link #authenticate}. Deleting the credential mid-flow changes the
 * answer of the "has an OTP credential" conditions that route this very login,
 * and Keycloak re-evaluates them: on 26.2.5 the user was sent into the enrol
 * branch and asked for a recovery code again, forever.
 *
 * <p>It acts only when the recovery form beside it SUCCEEDED in this login;
 * otherwise (the form was skipped, or is SETUP_REQUIRED) no code was used.
 */
public class RecoveryReenrolAuthenticator implements AuthenticationFlowCallback {

    private static final Logger log = Logger.getLogger(RecoveryReenrolAuthenticator.class);

    static final String RECOVERY_FORM = MfaConstants.RECOVERY_CODE_FORM;
    static final String NOTE_REMOVE_OTP = "gorsso.reenrol.remove_otp";

    private final KeycloakSession session;

    RecoveryReenrolAuthenticator(KeycloakSession session) {
        this.session = session;
    }

    static boolean recoveryCodeAccepted(AuthenticationFlowContext context) {
        String parent = context.getExecution().getParentFlow();
        Map<String, AuthenticationSessionModel.ExecutionStatus> status =
                context.getAuthenticationSession().getExecutionStatus();
        return context.getRealm().getAuthenticationExecutionsStream(parent)
                .filter(e -> RECOVERY_FORM.equals(e.getAuthenticator()))
                .anyMatch(e -> status.get(e.getId()) == AuthenticationSessionModel.ExecutionStatus.SUCCESS);
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        if (!recoveryCodeAccepted(context)) {
            context.success();
            return;
        }
        UserModel user = context.getUser();
        AuthenticatorConfigModel cfg = context.getAuthenticatorConfig();
        boolean removeExisting = removeExisting(cfg == null ? null : cfg.getConfig());

        user.addRequiredAction(UserModel.RequiredAction.CONFIGURE_TOTP);
        if (removeExisting) {
            context.getAuthenticationSession().setAuthNote(NOTE_REMOVE_OTP, "true");
        }
        forwardRecoveryUse(context, user, removeExisting);
        context.success();
    }

    /** Config {@code remove_existing}: on unless explicitly "false" (any case). */
    static boolean removeExisting(Map<String, String> config) {
        return config == null
                || !"false".equalsIgnoreCase(config.get(RecoveryReenrolAuthenticatorFactory.CONF_REMOVE_EXISTING));
    }

    @Override
    public void onParentFlowSuccess(AuthenticationFlowContext context) {
    }

    @Override
    public void onTopFlowSuccess(AuthenticationFlowModel topFlow) {
        if (session == null) {
            return;
        }
        AuthenticationSessionModel authSession = session.getContext().getAuthenticationSession();
        if (authSession == null || !"true".equals(authSession.getAuthNote(NOTE_REMOVE_OTP))) {
            return;
        }
        authSession.removeAuthNote(NOTE_REMOVE_OTP);
        UserModel user = authSession.getAuthenticatedUser();
        RealmModel realm = authSession.getRealm();
        if (user == null) {
            return;
        }
        List<CredentialModel> otp = user.credentialManager()
                .getStoredCredentialsByTypeStream(OTPCredentialModel.TYPE).toList();
        for (CredentialModel credential : otp) {
            user.credentialManager().removeStoredCredentialById(credential.getId());
            // Same pair Keycloak emits itself (DeleteCredentialAction): the
            // current REMOVE_CREDENTIAL plus the deprecated REMOVE_TOTP.
            EventBuilder event = new EventBuilder(realm, session, session.getContext().getConnection())
                    .user(user).client(authSession.getClient())
                    .detail(Details.USERNAME, user.getUsername())
                    .detail(Details.CREDENTIAL_TYPE, OTPCredentialModel.TYPE)
                    .detail(Details.CREDENTIAL_ID, credential.getId())
                    .detail("reason", "recovery_code_login");
            event.clone().event(EventType.REMOVE_CREDENTIAL).success();
            event.clone().event(EventType.REMOVE_TOTP).success();
        }
        log.infof("recovery-code login for '%s': removed %d OTP credential(s), re-enrolment required",
                user.getUsername(), otp.size());
    }

    /**
     * Posted here, not left to the LOGIN event: by the time LOGIN is sent the
     * required-action phase (CONFIGURE_TOTP, just added) has replaced the
     * details, and nothing on it says a recovery code was used.
     */
    private static void forwardRecoveryUse(AuthenticationFlowContext context, UserModel user, boolean removed) {
        AuditEventListenerFactory forwarder = AuditEventListenerFactory.of(context.getSession());
        if (forwarder == null) {
            return;
        }
        forwarder.forward(AuditPayloads.recoveryCodeUsed(user.getUsername(), user.getId(),
                context.getAuthenticationSession().getClient().getClientId(), context.getRealm().getName(),
                context.getConnection() == null ? null : context.getConnection().getRemoteAddr(), removed));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
    }

    @Override
    public boolean requiresUser() {
        return true;
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
