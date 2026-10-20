package rw.gov.risa.sso.wrapper.service.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Redis layout: {@code gorsso:oauth2:code:<sha256(code)>} → JSON {@link StoredCode}, TTL {@code AUTH_CODE_TTL}.
 * Only the hash of the code is a key, so a Redis dump does not hold redeemable codes. Redeemed with
 * {@code GETDEL} (Redis ≥ 6.2): one atomic step, so two concurrent redemptions cannot both win and a
 * redemption that later fails has still spent the code. Redis down → 503 (fail closed).
 */
@Component
public class RedisAuthCodeStore implements AuthCodeStore {

    private static final Logger log = LoggerFactory.getLogger(RedisAuthCodeStore.class);
    static final String PREFIX = "gorsso:oauth2:code:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final GatewayProperties props;

    public RedisAuthCodeStore(StringRedisTemplate redis, ObjectMapper json, GatewayProperties props) {
        this.redis = redis;
        this.json = json;
        this.props = props;
    }

    @Override
    public String issue(StoredCode code) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            redis.opsForValue().set(key(raw), json.writeValueAsString(code), props.codeTtl());
        } catch (IOException | RuntimeException e) {
            log.error("gateway: could not store an authorization code: {}", e.getClass().getSimpleName());
            throw GatewayErrors.upstream("Authorization code store unavailable");
        }
        return raw;
    }

    @Override
    public Optional<StoredCode> consume(String code) {
        if (code == null || code.isBlank() || code.length() > 512) {
            return Optional.empty();
        }
        String value;
        try {
            value = redis.opsForValue().getAndDelete(key(code));
        } catch (RuntimeException e) {
            log.error("gateway: authorization code store unavailable: {}", e.getClass().getSimpleName());
            throw GatewayErrors.upstream("Authorization code store unavailable");
        }
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value, StoredCode.class));
        } catch (IOException e) {
            log.error("gateway: unreadable authorization code record");
            return Optional.empty();
        }
    }

    static String key(String code) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
