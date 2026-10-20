package rw.gov.risa.sso.mfa;

import rw.gov.risa.sso.mfa.policy.MfaConstants;

import org.keycloak.Config;
import org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticator;
import org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

import java.util.List;

public class CredentialConditionFactory implements ConditionalAuthenticatorFactory {

    public static final String PROVIDER_ID = MfaConstants.PROVIDER_CREDENTIAL_CONDITION;
    public static final String CONF_TYPE = "credential_type";
    public static final String CONF_PRESENT = "present";

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
        return "Condition - GoRSSO user has credential";
    }

    @Override
    public String getHelpText() {
        return "Matches when the user has (present=true) or has not (present=false) a stored credential of the given type.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
                .property().name(CONF_TYPE).label("Credential type")
                .helpText("Stored credential type, e.g. otp.").type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue(MfaConstants.OTP_CREDENTIAL_TYPE).add()
                .property().name(CONF_PRESENT).label("Match when present")
                .helpText("On: match users who HAVE one. Off: match users who have none.")
                .type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue("true").add()
                .build();
    }

    @Override
    public ConditionalAuthenticator getSingleton() {
        return CredentialCondition.SINGLETON;
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
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
