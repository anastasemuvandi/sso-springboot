package rw.gov.risa.sso.wrapper.policy.joinkey;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The login gate's applicability and refusal body — enforced at every token door
 * ({@code /auth/login}, {@code /oauth2/authorize-internal}, {@code /oauth2/token} for
 * {@code authorization_code} and {@code refresh_token}).
 *
 * <p>Skipped for: no username (service-account token), {@code JOIN_KEY_ENFORCE=false}, an exempt
 * control-plane client, and — unless {@code JOIN_KEY_ENFORCE_UNSET_CLIENTS} — a client with no
 * explicit column. An infrastructure failure while checking <b>fails open</b> (the service allows
 * the login); a refusal is 403 with {@link #refusalBody}, audited {@code LOGIN_DENIED}.
 */
public final class JoinKeyGate {

    private JoinKeyGate() {
    }

    /** Before any lookup: does the gate apply to this (username, client)? */
    public static boolean appliesTo(String username, String clientId, boolean enforce,
                                    Collection<String> exemptClients) {
        if (username == null || username.isEmpty() || !enforce) {
            return false;
        }
        return exemptClients == null || !exemptClients.contains(clientId);
    }

    /** After reading the client's setting: is it enforced? */
    public static boolean enforcedFor(JoinColumnSetting setting, boolean enforceUnsetClients) {
        return setting.explicit() || enforceUnsetClients;
    }

    /**
     * The 403 body: {@code error, error_description, join_column, claim, user_attribute}.
     */
    public static Map<String, Object> refusalBody(JoinKeyResolution resolution, JoinColumnSetting setting) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", resolution.error() == null ? null : resolution.error().code());
        out.put("error_description", resolution.message());
        out.put("join_column", setting.column().value());
        out.put("claim", setting.claim());
        out.put("user_attribute", setting.spec().source());
        return out;
    }
}
