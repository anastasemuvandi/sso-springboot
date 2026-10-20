package rw.gov.risa.sso.wrapper.keycloak;

import java.util.function.Function;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.config.SsoProperties;

/**
 * The only place a Keycloak Admin API client is built.
 *
 * <p><b>A fresh client on every call, never cached.</b> A long-lived client keeps a refresh token
 * that dies on a Keycloak restart or key rotation, after which every later call fails until the
 * process restarts. Build one per unit of work with {@link #asServiceAccount(Function)} (several
 * Admin API calls inside one lambda share the client and its single token) and let it close.
 *
 * <p>Two identities:
 * <ul>
 *   <li><b>service account</b> — the {@code sso-client} client-credentials grant in the managed
 *       realm (never the master admin). Platform-admin routes and background jobs.</li>
 *   <li><b>as caller</b> — the caller's own bearer token, nothing to fall back to. Delegated
 *       calls ({@code DELEGATED_EXECUTE_AS_CALLER=true}) use it so Keycloak's FGAP v2 permissions
 *       decide too. When the token expires the call fails; that is correct.</li>
 * </ul>
 */
@Component
public class KeycloakAdminFactory {

    private final SsoProperties.Keycloak kc;

    public KeycloakAdminFactory(SsoProperties props) {
        this.kc = props.keycloak();
    }

    /** Builds a new service-account client. The caller MUST close it (prefer {@link #asServiceAccount}). */
    public Keycloak newServiceAccountClient() {
        return KeycloakBuilder.builder()
                .serverUrl(kc.serverUrl())
                .realm(kc.realm())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(kc.clientId())
                .clientSecret(kc.clientSecret())
                .build();
    }

    /** Builds a new client that presents the caller's own token. The caller MUST close it. */
    public Keycloak newCallerClient(String callerAccessToken) {
        if (callerAccessToken == null || callerAccessToken.isBlank()) {
            // Never fall back to the service account: that would turn a delegated call into a
            // realm-wide one.
            throw new IllegalArgumentException("as-caller Keycloak client needs the caller's access token");
        }
        return Keycloak.getInstance(kc.serverUrl(), kc.realm(), kc.clientId(), callerAccessToken);
    }

    /** Runs {@code work} against the managed realm as the wrapper's service account. */
    public <T> T asServiceAccount(Function<RealmResource, T> work) {
        try (Keycloak keycloak = newServiceAccountClient()) {
            return work.apply(keycloak.realm(kc.realm()));
        }
    }

    /** Runs {@code work} against the managed realm as the caller. */
    public <T> T asCaller(String callerAccessToken, Function<RealmResource, T> work) {
        try (Keycloak keycloak = newCallerClient(callerAccessToken)) {
            return work.apply(keycloak.realm(kc.realm()));
        }
    }

    /** Runs {@code work} against another realm as the service account (only where a feature needs it). */
    public <T> T asServiceAccountInRealm(String realm, Function<RealmResource, T> work) {
        try (Keycloak keycloak = newServiceAccountClient()) {
            return work.apply(keycloak.realm(realm));
        }
    }

    public String realm() {
        return kc.realm();
    }
}
