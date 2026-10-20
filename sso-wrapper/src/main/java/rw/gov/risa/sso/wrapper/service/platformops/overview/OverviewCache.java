package rw.gov.risa.sso.wrapper.service.platformops.overview;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.service.platformops.PlatformOpsProperties;

/**
 * The platform overview's 60-second cache, shared by every wrapper instance through Redis
 * ({@code platform:overview}, JSON, TTL {@code overviewCacheTtl}). When Redis is unavailable it falls back
 * to this instance's memory with the same lifetime, so a Redis outage never makes the most expensive
 * page in the console uncached. Never throws.
 */
@Component
public class OverviewCache {

    private static final Logger log = LoggerFactory.getLogger(OverviewCache.class);
    public static final String KEY = "platform:overview";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;
    private final Clock clock;
    private volatile Local local;

    private record Local(OverviewView value, Instant at) {
    }

    @Autowired
    public OverviewCache(StringRedisTemplate redis, ObjectMapper json, PlatformOpsProperties props) {
        this(redis, json, props.overviewCacheTtl(), Clock.systemUTC());
    }

    public OverviewCache(StringRedisTemplate redis, ObjectMapper json, Duration ttl, Clock clock) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
        this.clock = clock;
    }

    public Optional<OverviewView> get() {
        try {
            String raw = redis.opsForValue().get(KEY);
            if (raw != null && !raw.isBlank()) {
                return Optional.of(json.readValue(raw, OverviewView.class));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.debug("overview cache: Redis read failed ({}); using local memory", e.toString());
            Local l = local;
            return l != null && l.at().plus(ttl).isAfter(clock.instant()) ? Optional.of(l.value()) : Optional.empty();
        }
    }

    public void put(OverviewView value) {
        local = new Local(value, clock.instant());
        try {
            redis.opsForValue().set(KEY, json.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.debug("overview cache: Redis write failed ({}); kept in local memory", e.toString());
        }
    }
}
