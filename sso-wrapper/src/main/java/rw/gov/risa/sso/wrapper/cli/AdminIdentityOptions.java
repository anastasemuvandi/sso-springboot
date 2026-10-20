package rw.gov.risa.sso.wrapper.cli;

import java.util.Set;

import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.service.platformops.fgap.AdminSession;

/**
 * The admin identity an Admin-API ops command uses: the {@code sso-client} service account unless
 * {@code --admin-user} is given; the password comes from {@code --admin-password} or the
 * {@code KEYCLOAK_ADMIN_PASSWORD} environment variable (never echoed). {@code --url} / {@code --realm}
 * override {@code KEYCLOAK_SERVER_URL} / {@code KEYCLOAK_REALM}.
 */
final class AdminIdentityOptions {

    static final Set<String> OPTIONS = Set.of("url", "realm", "admin-user", "admin-password", "admin-realm");

    private AdminIdentityOptions() {
    }

    static String url(CliArgs args, SsoProperties sso) {
        return args.value("url").orElse(sso.keycloak().serverUrl());
    }

    static String realm(CliArgs args, SsoProperties sso) {
        return args.value("realm").orElse(sso.keycloak().realm());
    }

    static AdminSession open(CliArgs args, SsoProperties sso, KeycloakAdminFactory factory) {
        if (args.value("admin-user").isPresent()) {
            String password = args.value("admin-password").orElseGet(() -> env("KEYCLOAK_ADMIN_PASSWORD"));
            if (password == null || password.isBlank()) {
                throw new IllegalArgumentException("--admin-user needs --admin-password or KEYCLOAK_ADMIN_PASSWORD");
            }
            return AdminSession.password(url(args, sso), realm(args, sso), args.value("admin-realm").orElse("master"),
                    args.value("admin-user").get(), password);
        }
        if (args.value("url").isPresent() || args.value("realm").isPresent()) {
            SsoProperties.Keycloak k = sso.keycloak();
            SsoProperties.Keycloak other = new SsoProperties.Keycloak(url(args, sso), k.publicUrl(), k.localIssuer(),
                    realm(args, sso), k.clientId(), k.clientSecret(), k.exemptClients());
            SsoProperties moved = new SsoProperties(sso.debug(), sso.environment(), sso.apiBaseUrl(), other, sso.cors());
            return AdminSession.serviceAccount(new KeycloakAdminFactory(moved), moved);
        }
        return AdminSession.serviceAccount(factory, sso);
    }

    static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v;
    }
}
