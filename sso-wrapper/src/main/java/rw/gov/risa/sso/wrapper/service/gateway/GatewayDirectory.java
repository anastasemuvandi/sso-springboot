package rw.gov.risa.sso.wrapper.service.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;

/**
 * The gateway's Admin API reads and writes, all as the wrapper's service account. Every failure that is
 * not a plain "no such object" becomes 503 {@code upstream_unavailable}: the gateway fails CLOSED.
 */
@Component
public class GatewayDirectory {

    private static final Logger log = LoggerFactory.getLogger(GatewayDirectory.class);

    private final KeycloakExecutors executors;
    private final KeycloakDirectory directory;

    public GatewayDirectory(KeycloakExecutors executors, KeycloakDirectory directory) {
        this.executors = executors;
        this.directory = directory;
    }

    /** The realm client by clientId, or empty. */
    public Optional<RegisteredClient> findClient(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        return call("client lookup", realm -> directory.findClient(realm, clientId).map(RegisteredClient::of));
    }

    /**
     * Constant-time comparison of a PRESENTED secret with the client's. The stored secret is read only to
     * verify what the caller sent; it never leaves this method (spec §5.1: never look a secret up on the
     * caller's behalf).
     */
    public boolean secretMatches(RegisteredClient client, String presented) {
        if (presented == null || presented.isEmpty()) {
            return false;
        }
        String stored = call("client secret check", realm -> {
            CredentialRepresentation c = realm.clients().get(client.uuid()).getSecret();
            return c == null ? null : c.getValue();
        });
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    /** Is session {@code sid} of user {@code userId} still alive in Keycloak? */
    public boolean sessionActive(String userId, String sid) {
        return call("session lookup", realm -> {
            try {
                List<UserSessionRepresentation> sessions = realm.users().get(userId).getUserSessions();
                return sessions != null && sessions.stream().anyMatch(s -> sid.equals(s.getId()));
            } catch (NotFoundException e) {
                return false;
            }
        });
    }

    /** End one SSO session (every token of it stops refreshing). A session already gone is not an error. */
    public void endSession(String sid) {
        call("session logout", realm -> {
            try {
                realm.deleteSession(sid, false);
            } catch (NotFoundException e) {
                log.debug("session already ended");
            }
            return null;
        });
    }

    public List<GroupRepresentation> userGroups(String userId) {
        return call("user groups", realm -> directory.userGroups(realm, userId));
    }

    public List<ClientRepresentation> clients() {
        return call("client list", directory::listClients);
    }

    private <T> T call(String what, Function<RealmResource, T> work) {
        try {
            return executors.serviceAccount().call(work);
        } catch (ApiException e) {
            throw e;
        } catch (ProcessingException e) {
            log.error("gateway: {} failed, Keycloak unreachable: {}", what, e.getMessage());
            throw GatewayErrors.upstream("Identity provider unreachable");
        } catch (WebApplicationException e) {
            log.error("gateway: {} failed with HTTP {}", what,
                    e.getResponse() == null ? "?" : e.getResponse().getStatus());
            throw GatewayErrors.upstream("Identity provider error");
        } catch (RuntimeException e) {
            log.error("gateway: {} failed: {}", what, e.toString());
            throw GatewayErrors.upstream("Identity provider error");
        }
    }
}
