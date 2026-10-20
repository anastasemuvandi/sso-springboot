package rw.gov.risa.sso.wrapper.service.keycloak;

import java.util.function.Consumer;
import java.util.function.Function;

import org.keycloak.admin.client.resource.RealmResource;

/**
 * WHO executes a unit of Keycloak Admin API work: the wrapper's service account, or the caller's own
 * token. Shared services take one as a parameter so a delegated feature can choose
 * ({@code DELEGATED_EXECUTE_AS_CALLER}) while platform features pass the service account.
 *
 * <p>Every {@link #call} builds a fresh admin client (never cached, see {@code KeycloakAdminFactory})
 * and closes it afterwards, so group several Admin API calls inside one lambda.
 *
 * <p>Obtain instances from {@link KeycloakExecutors}. In unit tests use {@link #of(RealmResource)}.
 */
public interface KeycloakExec {

    <T> T call(Function<RealmResource, T> work);

    default void run(Consumer<RealmResource> work) {
        call(realm -> {
            work.accept(realm);
            return null;
        });
    }

    /** True when this executes with the caller's token (Keycloak's FGAP v2 decides too). */
    boolean asCaller();

    /** "service-account" | "caller", for logs and audit. */
    default String label() {
        return asCaller() ? "caller" : "service-account";
    }

    /** A fixed realm (tests, or a realm resource already opened by the caller). */
    static KeycloakExec of(RealmResource realm) {
        return of(realm, false);
    }

    static KeycloakExec of(RealmResource realm, boolean asCaller) {
        return new KeycloakExec() {
            @Override
            public <T> T call(Function<RealmResource, T> work) {
                return work.apply(realm);
            }

            @Override
            public boolean asCaller() {
                return asCaller;
            }
        };
    }
}
