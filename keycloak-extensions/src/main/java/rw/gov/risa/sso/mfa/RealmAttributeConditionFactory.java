package rw.gov.risa.sso.mfa;

import rw.gov.risa.sso.mfa.policy.MfaConstants;

import org.keycloak.Config;
import org.keycloak.authentication.AuthenticationFlowCallbackFactory;
import org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticator;
import org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

import java.util.List;

/**
 * Registers {@link RealmAttributeCondition} as "Condition - GoRSSO realm attribute".
 *
 * <p>Implements {@link AuthenticationFlowCallbackFactory} so Keycloak calls the
 * condition back when its sub-flow succeeds — that callback is where the level
 * of authentication is recorded.
 */
public class RealmAttributeConditionFactory implements ConditionalAuthenticatorFactory, AuthenticationFlowCallbackFactory {

    public static final String PROVIDER_ID = MfaConstants.PROVIDER_REALM_ATTRIBUTE_CONDITION;

    public static final String CONF_ATTRIBUTE = "attribute";
    public static final String CONF_EXPECTED = "expected";
    public static final String CONF_WHEN_UNSET = "when_unset";
    public static final String CONF_LOA = "loa";

    public static final String DEFAULT_ATTRIBUTE = MfaConstants.LEGACY_ATTRIBUTE;

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
        return "Condition - GoRSSO realm attribute";
    }

    @Override
    public String getHelpText() {
        return "Matches when a realm attribute equals the expected value (default: mfa_totp_enforced = true, "
                + "unset counts as true). Optionally records a level of authentication when its sub-flow succeeds.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
                .property().name(CONF_ATTRIBUTE).label("Realm attribute")
                .helpText("Name of the realm attribute to read.")
                .type(ProviderConfigProperty.STRING_TYPE).defaultValue(DEFAULT_ATTRIBUTE).add()
                .property().name(CONF_EXPECTED).label("Expected value")
                .helpText("Case-insensitive value that makes the condition match.")
                .type(ProviderConfigProperty.STRING_TYPE).defaultValue("true").add()
                .property().name(CONF_WHEN_UNSET).label("Match when unset")
                .helpText("Result when the attribute is absent or blank. On by default: enforcement is the safe default.")
                .type(ProviderConfigProperty.BOOLEAN_TYPE).defaultValue("true").add()
                .property().name(CONF_LOA).label("Level of authentication on success")
                .helpText("Level recorded when the guarded sub-flow succeeds (drives the acr claim). Blank = none.")
                .type(ProviderConfigProperty.STRING_TYPE).defaultValue("").add()
                .build();
    }

    @Override
    public ConditionalAuthenticator getSingleton() {
        return new RealmAttributeCondition(null);
    }

    @Override
    public ConditionalAuthenticator create(KeycloakSession session) {
        // Not the singleton: onTopFlowSuccess has no context argument and needs the session.
        return new RealmAttributeCondition(session);
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
