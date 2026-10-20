package rw.gov.risa.sso.wrapper.policy.setuplink;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The link store (Redis in production). Key layout of the reference, for implementers:
 *
 * <pre>
 *   setup-token:&lt;sha256&gt;          LinkRecord                       TTL = link lifespan
 *   setup-token-user:&lt;uid&gt;        sha256 of the ONE live link       TTL = link lifespan
 *   setup-clients:&lt;uid&gt;           bound client ids                  TTL = 30 days
 *   setup-link:last:&lt;uid&gt;         last resend (epoch seconds)       TTL = cooldown
 *   setup-link:day:&lt;uid&gt;:&lt;yyyymmdd&gt;  resend count that day        TTL = 1 day
 *   setup-link:delivery:&lt;uid&gt;     Delivery                          TTL = 30 days
 * </pre>
 *
 * Implementations hold no rules; {@link SetupLinks} decides everything.
 */
public interface SetupLinkStore {

    Optional<LinkRecord> findRecord(String hash);

    void saveRecord(LinkRecord record, Duration ttl);

    void deleteRecord(String hash);

    Optional<String> currentHash(String userId);

    void setCurrentHash(String userId, String hash, Duration ttl);

    void deleteCurrentHash(String userId);

    List<String> boundClients(String userId);

    void setBoundClients(String userId, List<String> clientIds, Duration ttl);

    Optional<Delivery> lastDelivery(String userId);

    void saveDelivery(String userId, Delivery delivery, Duration ttl);

    void deleteDelivery(String userId);

    /** When the last holder resend went out, if inside the cooldown. */
    Optional<Instant> lastSentAt(String userId);

    /** Resends sent to the account on {@code day} (UTC). */
    int sentOn(String userId, LocalDate day);

    /** Record one resend: last-sent (TTL cooldown) and the day counter (TTL one day). */
    void recordSent(String userId, LocalDate day, Instant now, Duration cooldown);
}
