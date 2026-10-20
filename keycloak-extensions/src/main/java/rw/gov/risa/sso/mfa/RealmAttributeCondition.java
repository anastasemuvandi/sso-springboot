package rw.gov.risa.sso.mfa;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowCallback;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticator;
import org.keycloak.authentication.authenticators.util.AcrStore;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import rw.gov.risa.sso.mfa.policy.MfaConstants;
import rw.gov.risa.sso.mfa.policy.MfaPolicy;

import java.util.Map;

/**
 * Condition: "TOTP applies to a login to this client" — the realm mode and
 * the client's own setting, decided by the shared {@link MfaPolicy} (mfa-policy module). (Originally a
 * single realm switch, hence the provider id.)
 *
 * <p>Keycloak 26.2 ships no condition that reads a <em>realm</em> attribute —
 * "Condition - user attribute" reads the user's — so the realm-wide TOTP switch
 * needs this. It is read from {@link RealmModel#getAttribute} on every
 * evaluation, and a realm update invalidates the realm cache cluster-wide, so a
 * flip takes effect on the next login with no restart.
 *
 * <p>It also does one thing a plain condition cannot: when the sub-flow it
 * guards SUCCEEDS, it records the configured level of authentication. That is
 * what puts {@code acr=2} in the token. Keycloak's own "Condition - Level of
 * Authentication" cannot be used as the gate here: with no {@code acr_values}
 * requested (the normal case for every client) it evaluates a level-2 step to
 * false once level 1 is reached, and the OTP step would never run. See
 * docs/implemented/mfa-totp.md, "Why a custom condition".
 */
public class RealmAttributeCondition implements ConditionalAuthenticator, AuthenticationFlowCallback {

    private static final Logger log = Logger.getLogger(RealmAttributeCondition.class);

    private final KeycloakSession session;

    RealmAttributeCondition(KeycloakSession session) {
        this.session = session;
    }

    /** Pure decision, unit-tested: unset or blank falls back to {@code whenUnset}. */
    static boolean isOn(String value, String expected, boolean whenUnset) {
        return MfaPolicy.attributeMatches(value, expected, whenUnset);
    }

    @Override
    public boolean matchCondition(AuthenticationFlowContext context) {
        // Realm mode + this client's own setting (MfaPolicy). The legacy
        // single switch is read through the configured `attribute`, so an
        // execution configured before the modes existed keeps working.
        Map<String, String> cfg = config(context.getAuthenticatorConfig());
        String legacy = cfg.getOrDefault(RealmAttributeConditionFactory.CONF_ATTRIBUTE,
                RealmAttributeConditionFactory.DEFAULT_ATTRIBUTE);
        ClientModel client = context.getAuthenticationSession().getClient();
        boolean on = MfaPolicy.applies(
                context.getRealm().getAttribute(MfaConstants.MODE_ATTRIBUTE),
                context.getRealm().getAttribute(legacy),
                client == null ? null : client.getAttribute(MfaConstants.CLIENT_ATTRIBUTE));
        log.debugf("client '%s': TOTP -> %s", client == null ? null : client.getClientId(), on);
        return on;
    }

    @Override
    public void onParentFlowSuccess(AuthenticationFlowContext context) {
        Integer loa = level(config(context.getAuthenticatorConfig()));
        if (loa == null) {
            return;
        }
        new AcrStore(context.getSession(), context.getAuthenticationSession()).setLevelAuthenticated(loa);
        context.getEvent().detail("gorsso_mfa", "completed");
    }

    @Override
    public void onTopFlowSuccess(AuthenticationFlowModel topFlow) {
        // Same as Keycloak's LoA condition: carry the level map into the user
        // session so an SSO-cookie login later in the session still reads as
        // the level reached here (bounded by the flow's LoA max-age).
        if (session == null) {
            return;
        }
        AuthenticationSessionModel authSession = session.getContext().getAuthenticationSession();
        if (authSession != null && authSession.getAuthNote(Constants.LOA_MAP) != null) {
            authSession.setUserSessionNote(Constants.LOA_MAP, authSession.getAuthNote(Constants.LOA_MAP));
        }
    }

    static Integer level(Map<String, String> cfg) {
        String raw = cfg.get(RealmAttributeConditionFactory.CONF_LOA);
        Integer level = MfaPolicy.parseLevel(raw).orElse(null);
        if (level == null && raw != null && !raw.isBlank()) {
            log.warnf("ignoring non-numeric '%s' = '%s'", RealmAttributeConditionFactory.CONF_LOA, raw);
        }
        return level;
    }

    private static Map<String, String> config(AuthenticatorConfigModel model) {
        return model == null || model.getConfig() == null ? Map.of() : model.getConfig();
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        // A condition renders nothing, so it never receives a form post.
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
}
