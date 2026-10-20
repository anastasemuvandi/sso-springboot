package rw.gov.risa.sso.wrapper.service.setuplink;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.policy.setuplink.Delivery;
import rw.gov.risa.sso.wrapper.policy.setuplink.InMemorySetupLinkStore;
import rw.gov.risa.sso.wrapper.policy.setuplink.LinkRecord;
import rw.gov.risa.sso.wrapper.policy.setuplink.SetupLinkStore;
import rw.gov.risa.sso.wrapper.service.mail.MailDeliveryLog;

/**
 * The set-password link store on Redis (reference key layout; only the token's SHA-256 is stored):
 *
 * <pre>
 *   setup-token:&lt;sha256&gt;                 {"user_id","hash","client_ids":[..],"expires_at":epochSeconds}  TTL 72 h
 *   setup-token-user:&lt;uid&gt;               sha256 of the ONE live link                                    TTL 72 h
 *   setup-clients:&lt;uid&gt;                  ["client-id", ...] bound applications                           TTL 30 d
 *   setup-link:last:&lt;uid&gt;                epoch seconds of the last holder resend                         TTL 5 min
 *   setup-link:day:&lt;uid&gt;:&lt;yyyyMMdd&gt;     holder resends that UTC day                                     TTL 1 d
 *   setup-link:delivery:&lt;uid&gt;            see {@link MailDeliveryLog}                                     TTL 30 d
 * </pre>
 *
 * When Redis is unreachable each operation falls back to an in-process store (the reference's
 * behaviour), logged at WARN.
 */
@Component
public class RedisSetupLinkStore implements SetupLinkStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSetupLinkStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final StringRedisTemplate redis;
    private final MailDeliveryLog deliveries;
    private final InMemorySetupLinkStore memory;

    @Autowired
    public RedisSetupLinkStore(StringRedisTemplate redis, MailDeliveryLog deliveries) {
        this(redis, deliveries, Clock.systemUTC());
    }

    public RedisSetupLinkStore(StringRedisTemplate redis, MailDeliveryLog deliveries, Clock clock) {
        this.redis = redis;
        this.deliveries = deliveries;
        this.memory = new InMemorySetupLinkStore(clock);
    }

    static String tokenKey(String hash) {
        return "setup-token:" + hash;
    }

    static String currentKey(String uid) {
        return "setup-token-user:" + uid;
    }

    static String clientsKey(String uid) {
        return "setup-clients:" + uid;
    }

    static String lastKey(String uid) {
        return "setup-link:last:" + uid;
    }

    static String dayKey(String uid, LocalDate day) {
        return "setup-link:day:" + uid + ":" + DAY.format(day);
    }

    private <T> T redisOr(Supplier<T> onRedis, Supplier<T> onMemory) {
        try {
            return onRedis.get();
        } catch (RuntimeException e) {
            log.warn("setup-link store: Redis unavailable, using in-process store: {}", e.getMessage());
            return onMemory.get();
        }
    }

    private void redisOr(Runnable onRedis, Runnable onMemory) {
        redisOr(() -> {
            onRedis.run();
            return null;
        }, () -> {
            onMemory.run();
            return null;
        });
    }

    @Override
    public Optional<LinkRecord> findRecord(String hash) {
        return redisOr(() -> {
            String raw = redis.opsForValue().get(tokenKey(hash));
            return raw == null ? Optional.<LinkRecord>empty() : parseRecord(raw);
        }, () -> memory.findRecord(hash));
    }

    @Override
    public void saveRecord(LinkRecord record, Duration ttl) {
        redisOr(() -> redis.opsForValue().set(tokenKey(record.hash()), writeRecord(record), ttl),
                () -> memory.saveRecord(record, ttl));
    }

    @Override
    public void deleteRecord(String hash) {
        redisOr(() -> redis.delete(tokenKey(hash)), () -> memory.deleteRecord(hash));
    }

    @Override
    public Optional<String> currentHash(String userId) {
        return redisOr(() -> Optional.ofNullable(redis.opsForValue().get(currentKey(userId))),
                () -> memory.currentHash(userId));
    }

    @Override
    public void setCurrentHash(String userId, String hash, Duration ttl) {
        redisOr(() -> redis.opsForValue().set(currentKey(userId), hash, ttl), () -> memory.setCurrentHash(userId, hash, ttl));
    }

    @Override
    public void deleteCurrentHash(String userId) {
        redisOr(() -> redis.delete(currentKey(userId)), () -> memory.deleteCurrentHash(userId));
    }

    @Override
    public List<String> boundClients(String userId) {
        return redisOr(() -> {
            String raw = redis.opsForValue().get(clientsKey(userId));
            if (raw == null) {
                return List.<String>of();
            }
            try {
                List<?> l = JSON.readValue(raw, List.class);
                List<String> out = new ArrayList<>();
                l.forEach(o -> out.add(String.valueOf(o)));
                return out;
            } catch (Exception e) {
                return List.<String>of();
            }
        }, () -> memory.boundClients(userId));
    }

    @Override
    public void setBoundClients(String userId, List<String> clientIds, Duration ttl) {
        redisOr(() -> {
            try {
                redis.opsForValue().set(clientsKey(userId), JSON.writeValueAsString(clientIds), ttl);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }, () -> memory.setBoundClients(userId, clientIds, ttl));
    }

    @Override
    public Optional<Delivery> lastDelivery(String userId) {
        return deliveries.last(userId);
    }

    @Override
    public void saveDelivery(String userId, Delivery delivery, Duration ttl) {
        deliveries.save(userId, delivery, ttl);
    }

    @Override
    public void deleteDelivery(String userId) {
        deliveries.delete(userId);
    }

    @Override
    public Optional<Instant> lastSentAt(String userId) {
        return redisOr(() -> {
            String raw = redis.opsForValue().get(lastKey(userId));
            if (raw == null) {
                return Optional.<Instant>empty();
            }
            double seconds = Double.parseDouble(raw);
            return Optional.of(Instant.ofEpochMilli((long) (seconds * 1000)));
        }, () -> memory.lastSentAt(userId));
    }

    @Override
    public int sentOn(String userId, LocalDate day) {
        return redisOr(() -> {
            String raw = redis.opsForValue().get(dayKey(userId, day));
            return raw == null ? 0 : Integer.parseInt(raw);
        }, () -> memory.sentOn(userId, day));
    }

    @Override
    public void recordSent(String userId, LocalDate day, Instant now, Duration cooldown) {
        redisOr(() -> {
            redis.opsForValue().set(lastKey(userId), String.valueOf(now.toEpochMilli() / 1000.0), cooldown);
            redis.opsForValue().increment(dayKey(userId, day));
            redis.expire(dayKey(userId, day), Duration.ofDays(1));
        }, () -> memory.recordSent(userId, day, now, cooldown));
    }

    static String writeRecord(LinkRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", r.userId());
        m.put("hash", r.hash());
        m.put("client_ids", r.clientIds());
        m.put("expires_at", r.expiresAt() == null ? null : r.expiresAt().toEpochMilli() / 1000.0);
        try {
            return JSON.writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Optional<LinkRecord> parseRecord(String raw) {
        try {
            Map<?, ?> m = JSON.readValue(raw, Map.class);
            Object uid = m.get("user_id");
            Object hash = m.get("hash");
            if (uid == null || hash == null) {
                return Optional.empty();
            }
            List<String> clients = new ArrayList<>();
            if (m.get("client_ids") instanceof List<?> l) {
                l.forEach(o -> clients.add(String.valueOf(o)));
            }
            Instant expires = m.get("expires_at") instanceof Number n
                    ? Instant.ofEpochMilli((long) (n.doubleValue() * 1000)) : null;
            return Optional.of(new LinkRecord(String.valueOf(uid), String.valueOf(hash), clients, expires));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
