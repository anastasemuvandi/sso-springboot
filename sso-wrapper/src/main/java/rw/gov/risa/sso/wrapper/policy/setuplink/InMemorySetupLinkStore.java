package rw.gov.risa.sso.wrapper.policy.setuplink;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-process {@link SetupLinkStore} honouring TTLs against a {@link Clock}. For tests and as the
 * reference's in-process fallback; production uses Redis.
 */
public final class InMemorySetupLinkStore implements SetupLinkStore {

    private record Entry<T>(T value, Instant expires) {
    }

    private final Clock clock;
    private final Map<String, Entry<Object>> data = new ConcurrentHashMap<>();

    public InMemorySetupLinkStore(Clock clock) {
        this.clock = clock;
    }

    private void put(String key, Object value, Duration ttl) {
        data.put(key, new Entry<>(value, ttl == null ? null : clock.instant().plus(ttl)));
    }

    @SuppressWarnings("unchecked")
    private <T> Optional<T> get(String key) {
        Entry<Object> e = data.get(key);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expires() != null && !e.expires().isAfter(clock.instant())) {
            data.remove(key);
            return Optional.empty();
        }
        return Optional.of((T) e.value());
    }

    /** Number of live keys (for tests). */
    public int size() {
        data.keySet().forEach(this::get);
        return data.size();
    }

    @Override
    public Optional<LinkRecord> findRecord(String hash) {
        return get("setup-token:" + hash);
    }

    @Override
    public void saveRecord(LinkRecord record, Duration ttl) {
        put("setup-token:" + record.hash(), record, ttl);
    }

    @Override
    public void deleteRecord(String hash) {
        data.remove("setup-token:" + hash);
    }

    @Override
    public Optional<String> currentHash(String userId) {
        return get("setup-token-user:" + userId);
    }

    @Override
    public void setCurrentHash(String userId, String hash, Duration ttl) {
        put("setup-token-user:" + userId, hash, ttl);
    }

    @Override
    public void deleteCurrentHash(String userId) {
        data.remove("setup-token-user:" + userId);
    }

    @Override
    public List<String> boundClients(String userId) {
        return this.<List<String>>get("setup-clients:" + userId).orElse(List.of());
    }

    @Override
    public void setBoundClients(String userId, List<String> clientIds, Duration ttl) {
        put("setup-clients:" + userId, List.copyOf(clientIds), ttl);
    }

    @Override
    public Optional<Delivery> lastDelivery(String userId) {
        return get("setup-link:delivery:" + userId);
    }

    @Override
    public void saveDelivery(String userId, Delivery delivery, Duration ttl) {
        put("setup-link:delivery:" + userId, delivery, ttl);
    }

    @Override
    public void deleteDelivery(String userId) {
        data.remove("setup-link:delivery:" + userId);
    }

    @Override
    public Optional<Instant> lastSentAt(String userId) {
        return get("setup-link:last:" + userId);
    }

    @Override
    public int sentOn(String userId, LocalDate day) {
        return this.<Integer>get("setup-link:day:" + userId + ":" + day).orElse(0);
    }

    @Override
    public void recordSent(String userId, LocalDate day, Instant now, Duration cooldown) {
        put("setup-link:last:" + userId, now, cooldown);
        put("setup-link:day:" + userId + ":" + day, sentOn(userId, day) + 1, Duration.ofDays(1));
    }
}
