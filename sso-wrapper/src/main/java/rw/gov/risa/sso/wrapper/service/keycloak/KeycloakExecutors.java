package rw.gov.risa.sso.wrapper.service.keycloak;

import java.util.function.Function;

import org.keycloak.admin.client.resource.RealmResource;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;

/** Builds {@link KeycloakExec}s over {@link KeycloakAdminFactory}. */
@Component
public class KeycloakExecutors {

    private final KeycloakAdminFactory factory;

    public KeycloakExecutors(KeycloakAdminFactory factory) {
        this.factory = factory;
    }

    /** The wrapper's {@code sso-client} service account. */
    public KeycloakExec serviceAccount() {
        return new KeycloakExec() {
            @Override
            public <T> T call(Function<RealmResource, T> work) {
                return factory.asServiceAccount(work);
            }

            @Override
            public boolean asCaller() {
                return false;
            }
        };
    }

    /** The caller's own bearer token. Never falls back to the service account. */
    public KeycloakExec asCaller(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("as-caller execution needs the caller's access token");
        }
        return new KeycloakExec() {
            @Override
            public <T> T call(Function<RealmResource, T> work) {
                return factory.asCaller(accessToken, work);
            }

            @Override
            public boolean asCaller() {
                return true;
            }
        };
    }

    public KeycloakExec asCaller(CallerPrincipal caller) {
        return asCaller(caller.accessToken());
    }
}
