package rw.gov.risa.sso.mfa;

import rw.gov.risa.sso.mfa.policy.MfaConstants;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

public class StepUpAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = MfaConstants.PROVIDER_STEP_UP;

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENTS = {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.DISABLED,
    };

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "GoRSSO - request TOTP for this client";
    }

    @Override
    public String getReferenceCategory() {
        return null;
    }

    @Override
    public String getHelpText() {
        return "First step of the browser flow: when TOTP applies to the client (realm mfa_totp_mode, client "
                + "sso.client.mfa_required), requests level of authentication 2 so an SSO session steps up.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENTS;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return StepUpAuthenticator.SINGLETON;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
