package rw.gov.risa.sso.wrapper.service.institution;

import java.util.function.Function;

import org.keycloak.admin.client.resource.RealmResource;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.security.Callers;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;

/**
 * Which credential a DELEGATED Keycloak call uses (ADR-0002 §6 step 7). With
 * {@code DELEGATED_EXECUTE_AS_CALLER=true} (default) object-level calls run with the caller's own token,
 * so Keycloak's FGAP v2 permissions decide too; otherwise (or when the caller has no token) the service
 * account. The wrapper's own capability/scope checks run either way — this only picks the credential.
 *
 * <p>Always the service account, whatever the flag (and only AFTER the wrapper's scope check):
 * realm-wide catalogue queries (client lookup by clientId, role/client lists), client creation and
 * attribute writes (join column, MFA, scope), and a cross-cutting client's {@code sso-access-*} policies
 * ({@link #forClientPolicies}).
 */
@Component
public class DelegatedExecution {

    private final KeycloakExecutors executors;
    private final DelegationProperties props;

    public DelegatedExecution(KeycloakExecutors executors, DelegationProperties props) {
        this.executors = executors;
        this.props = props;
    }

    public boolean executeAsCaller() {
        return props.executeAsCaller();
    }

    /** As the caller when the flag is on and the caller carries a token; else the service account. */
    public KeycloakExec forCaller(CallerPrincipal caller) {
        if (props.executeAsCaller() && caller != null && caller.accessToken() != null
                && !caller.accessToken().isBlank()) {
            return executors.asCaller(caller.accessToken());
        }
        return executors.serviceAccount();
    }

    /** {@link #forCaller} for the current request's caller (401 when unauthenticated). */
    public KeycloakExec current() {
        return forCaller(Callers.current().orElseThrow(() -> ApiException.unauthorized("Authentication required")));
    }

    /** The executor for a client's {@code sso-access-*} policies: service account when cross-cutting. */
    public KeycloakExec forClientPolicies(CallerPrincipal caller, boolean crossCutting) {
        return crossCutting ? executors.serviceAccount() : forCaller(caller);
    }

    public KeycloakExec serviceAccount() {
        return executors.serviceAccount();
    }

    /** Run one unit of work with {@link #forCaller}. */
    public <T> T run(CallerPrincipal caller, Function<RealmResource, T> work) {
        return forCaller(caller).call(work);
    }
}
