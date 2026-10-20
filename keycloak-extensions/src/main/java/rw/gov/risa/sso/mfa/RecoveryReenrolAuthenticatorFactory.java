package rw.gov.risa.sso.mfa;

import rw.gov.risa.sso.mfa.policy.MfaConstants;

import org.keycloak.Config;
import org.keycloak.authentication.AuthenticationFlowCallbackFactory;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

import java.util.List;

public class RecoveryReenrolAuthenticatorFactory implements AuthenticatorFactory, AuthenticationFlowCallbackFactory {

    public static final String PROVIDER_ID = MfaConstants.PROVIDER_RECOVERY_REENROL;
    public static final String CONF_REMOVE_EXISTING = "remove_existing";

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
        return "GoRSSO - re-enrol TOTP after recovery code";
    }

    @Override
    public String getReferenceCategory() {
        return MfaConstants.OTP_CREDENTIAL_TYPE;
    }

    @Override
    public String getHelpText() {
        return "Place after 'Recovery Authentication Code Form'. When a recovery code was accepted, requires "
                + "CONFIGURE_TOTP and, once the flow has finished, removes the presumed-lost OTP credentials.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
                .property().name(CONF_REMOVE_EXISTING).label("Remove existing OTP credentials")
                .helpText("Revoke the presumed-lost device's secret. On by default.")
                .type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue("true").add()
                .build();
    }

    @Override
    public boolean isConfigurable() {
        return true;
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
        // Per session, not a singleton: onTopFlowSuccess has no context argument.
        return new RecoveryReenrolAuthenticator(session);
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
