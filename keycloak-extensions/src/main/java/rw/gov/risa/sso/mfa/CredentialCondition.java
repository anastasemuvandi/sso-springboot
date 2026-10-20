package rw.gov.risa.sso.mfa;

import rw.gov.risa.sso.mfa.policy.MfaConstants;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticator;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.Map;

/**
 * Condition: "the user has (or has not) a stored credential of type X".
 *
 * <p>Keycloak 26.2 has no such condition, and the obvious built-in shape — OTP
 * Form and the recovery sub-flow as ALTERNATIVES — does the wrong thing for a
 * user with no OTP: the unconfigured OTP Form is skipped as "setup required",
 * the engine falls through to the recovery branch, and a person who has never
 * enrolled is asked for a recovery code (or has recovery-code setup demanded
 * instead of TOTP). Splitting the 2FA sub-flow on this condition makes the two
 * cases explicit: no OTP -> enrol, OTP -> verify (with the recovery fallback).
 */
public class CredentialCondition implements ConditionalAuthenticator {

    static final CredentialCondition SINGLETON = new CredentialCondition();

    /** Pure decision, unit-tested. */
    static boolean matches(boolean hasCredential, boolean wantPresent) {
        return hasCredential == wantPresent;
    }

    @Override
    public boolean matchCondition(AuthenticationFlowContext context) {
        AuthenticatorConfigModel model = context.getAuthenticatorConfig();
        Map<String, String> cfg = model == null || model.getConfig() == null ? Map.of() : model.getConfig();
        String type = cfg.getOrDefault(CredentialConditionFactory.CONF_TYPE, MfaConstants.OTP_CREDENTIAL_TYPE);
        boolean wantPresent = !"false".equalsIgnoreCase(cfg.getOrDefault(CredentialConditionFactory.CONF_PRESENT, "true"));
        UserModel user = context.getUser();
        boolean has = user != null && user.credentialManager().getStoredCredentialsByTypeStream(type).findAny().isPresent();
        return matches(has, wantPresent);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
}
