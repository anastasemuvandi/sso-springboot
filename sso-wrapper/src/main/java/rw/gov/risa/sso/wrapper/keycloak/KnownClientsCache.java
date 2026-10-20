package rw.gov.risa.sso.wrapper.keycloak;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.keycloak.representations.idm.ClientRepresentation;
import org.springframework.stereotype.Component;

/**
 * clientIds registered in the realm, cached 60 s. A token is trusted only if its {@code azp}
 * names one of them (the {@code aud} claim is not security-bearing on Keycloak's defaults).
 */
@Component
public class KnownClientsCache {

    static final Duration TTL = Duration.ofSeconds(60);

    private final KeycloakAdminFactory keycloak;
    private final Clock clock;
    private volatile Set<String> clientIds = Set.of();
    private volatile Instant fetchedAt = Instant.EPOCH;

    @org.springframework.beans.factory.annotation.Autowired
    public KnownClientsCache(KeycloakAdminFactory keycloak) {
        this(keycloak, Clock.systemUTC());
    }

    KnownClientsCache(KeycloakAdminFactory keycloak, Clock clock) {
        this.keycloak = keycloak;
        this.clock = clock;
    }

    public boolean isKnown(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return false;
        }
        Set<String> ids = current();
        if (ids.contains(clientId)) {
            return true;
        }
        // A client created in the last minute: one forced refresh before refusing.
        return refresh().contains(clientId);
    }

    private Set<String> current() {
        if (!clientIds.isEmpty() && fetchedAt.plus(TTL).isAfter(clock.instant())) {
            return clientIds;
        }
        return refresh();
    }

    private synchronized Set<String> refresh() {
        if (!clientIds.isEmpty() && fetchedAt.plus(Duration.ofSeconds(2)).isAfter(clock.instant())) {
            return clientIds; // another thread just refreshed
        }
        Set<String> ids = keycloak.asServiceAccount(realm -> realm.clients().findAll().stream()
                .map(ClientRepresentation::getClientId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet()));
        clientIds = ids;
        fetchedAt = clock.instant();
        return ids;
    }

    /** Test hook. */
    public void invalidate() {
        clientIds = Set.of();
        fetchedAt = Instant.EPOCH;
    }
}
