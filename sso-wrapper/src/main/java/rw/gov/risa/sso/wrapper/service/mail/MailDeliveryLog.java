package rw.gov.risa.sso.wrapper.service.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.policy.setuplink.Delivery;

/**
 * Per-account record of how the LAST set-password email went — what the overview's "waiting for a
 * first password" buckets (failed / not_configured / unknown / sent) and the admin resend rule read.
 *
 * <pre>
 *   setup-link:delivery:&lt;userId&gt;   JSON {"status": "sent|failed|not_configured",
 *                                      "via": "provisioning|holder_resend|admin_resend",
 *                                      "at": ISO-8601 UTC}            TTL 30 days
 * </pre>
 *
 * Same key as the reference. Deleted when the link is spent (the password was set). Never throws:
 * a lost record only hides the admin's fallback, it changes no access.
 */
@Component
public class MailDeliveryLog {

    private static final Logger log = LoggerFactory.getLogger(MailDeliveryLog.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String KEY_PREFIX = "setup-link:delivery:";
    public static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public MailDeliveryLog(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    public MailDeliveryLog(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public static String key(String userId) {
        return KEY_PREFIX + userId;
    }

    /** Record {@code status} (a {@link DeliveryResult.Status#wire()}) sent {@code via}. */
    public Delivery record(String userId, String status, String via) {
        Delivery d = new Delivery(status, via,
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(clock.instant().atOffset(ZoneOffset.UTC)));
        save(userId, d, TTL);
        return d;
    }

    public void save(String userId, Delivery d, Duration ttl) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            String value = JSON.writeValueAsString(Map.of("status", d.status(), "via", d.via() == null ? "" : d.via(),
                    "at", d.at() == null ? "" : d.at()));
            redis.opsForValue().set(key(userId), value, ttl);
        } catch (Exception e) {
            log.warn("mail delivery log: could not record delivery for {}: {}", userId, e.getMessage());
        }
    }

    public Optional<Delivery> last(String userId) {
        try {
            String raw = redis.opsForValue().get(key(userId));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            Map<?, ?> m = JSON.readValue(raw, Map.class);
            Object status = m.get("status");
            if (status == null) {
                return Optional.empty();
            }
            return Optional.of(new Delivery(String.valueOf(status), str(m.get("via")), str(m.get("at"))));
        } catch (Exception e) {
            log.warn("mail delivery log: could not read delivery for {}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(String userId) {
        try {
            redis.delete(key(userId));
        } catch (Exception e) {
            log.warn("mail delivery log: could not delete delivery for {}: {}", userId, e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isEmpty() ? null : String.valueOf(o);
    }
}
