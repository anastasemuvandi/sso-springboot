package rw.gov.risa.sso.wrapper.service.ippis;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The delta watermark ({@code core/sync_state.py}), persisted in REDIS — shared by every wrapper
 * instance, unlike the reference's local JSON file. The value is IPPIS's own clock (the max
 * {@code updatedAt} seen), never a local row timestamp. Unreadable = "no watermark" (pull everything).
 */
@Component
public class WatermarkStore {

    private static final Logger log = LoggerFactory.getLogger(WatermarkStore.class);

    private final StringRedisTemplate redis;
    private final IppisProperties props;

    public WatermarkStore(StringRedisTemplate redis, IppisProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public Optional<Instant> read() {
        String v = redis.opsForValue().get(props.watermarkKey());
        if (v == null || v.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(v.strip()));
        } catch (DateTimeParseException e) {
            log.warn("ippis watermark at {} is unreadable; treating as none (a full delta follows)", props.watermarkKey());
            return Optional.empty();
        }
    }

    public void write(Instant watermark) {
        redis.opsForValue().set(props.watermarkKey(), watermark.toString());
    }

    public void clear() {
        redis.delete(props.watermarkKey());
    }
}
