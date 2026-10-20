package rw.gov.risa.sso.wrapper.service.platformops.fgap;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;

import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;

/**
 * An admin identity for the raw {@link AdminRest} client, for the length of one ops command: either the
 * wrapper's {@code sso-client} service account (default), or a master-realm admin by password grant
 * ({@code admin-cli}) for bootstrapping a realm the service account cannot yet manage. The token is taken
 * from the admin-client's token manager, which refreshes it. Close it.
 */
public final class AdminSession implements AutoCloseable {

    private final Keycloak keycloak;
    private final AdminRest rest;
    private final String identity;

    private AdminSession(Keycloak keycloak, String serverUrl, String realm, String identity) {
        this.keycloak = keycloak;
        this.identity = identity;
        this.rest = new AdminRest(serverUrl, realm, () -> keycloak.tokenManager().getAccessTokenString());
    }

    public static AdminSession serviceAccount(KeycloakAdminFactory factory, SsoProperties sso) {
        return new AdminSession(factory.newServiceAccountClient(), sso.keycloak().serverUrl(), sso.keycloak().realm(),
                "service account " + sso.keycloak().clientId());
    }

    public static AdminSession password(String serverUrl, String realm, String adminRealm, String username,
                                        String password) {
        Keycloak kc = KeycloakBuilder.builder().serverUrl(serverUrl).realm(adminRealm).clientId("admin-cli")
                .grantType(OAuth2Constants.PASSWORD).username(username).password(password).build();
        return new AdminSession(kc, serverUrl, realm, username + "@" + adminRealm);
    }

    public AdminRest rest() {
        return rest;
    }

    public String identity() {
        return identity;
    }

    @Override
    public void close() {
        keycloak.close();
    }
}
