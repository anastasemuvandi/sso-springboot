package rw.gov.risa.sso.wrapper.policy.clientaccess;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Clients that bypass per-client enforcement ({@code ACCESS_ENFORCEMENT_EXEMPT_CLIENTS}). */
public final class ClientAccessExemptions {

    /** The reference default: the SSO control plane plus Keycloak's built-ins. */
    public static final List<String> DEFAULT_EXEMPT_CLIENTS = List.of(
            "sso-client", "sso-admin-ui",
            "account", "account-console", "admin-cli", "broker", "realm-management", "security-admin-console");

    /** Keycloak's own clients: exempt, and left out of the per-user view entirely. */
    public static final Set<String> KEYCLOAK_BUILT_IN_CLIENTS = Set.of(
            "account", "account-console", "admin-cli", "broker", "realm-management", "security-admin-console");

    private ClientAccessExemptions() {
    }

    public static boolean isExempt(String clientId, Collection<String> exemptClients) {
        return clientId != null && exemptClients != null && exemptClients.contains(clientId);
    }

    public static boolean isKeycloakBuiltIn(String clientId) {
        return clientId != null && KEYCLOAK_BUILT_IN_CLIENTS.contains(clientId);
    }
}
