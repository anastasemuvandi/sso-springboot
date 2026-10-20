package rw.gov.risa.sso.wrapper.ratelimit;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.error.ErrorEnvelope;
import rw.gov.risa.sso.wrapper.security.Callers;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * Applies {@link RateLimit} plus the global default. Fails OPEN if Redis is unreachable: a
 * rate limiter outage must not take logins down (the event is logged).
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final ProxyManager<byte[]> buckets;
    private final RateLimitProperties props;
    private final ObjectMapper mapper;
    private final Map<String, Supplier<BucketConfiguration>> configs = new ConcurrentHashMap<>();

    public RateLimitInterceptor(ProxyManager<byte[]> buckets, RateLimitProperties props, ObjectMapper mapper) {
        this.buckets = buckets;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!props.enabled() || !(handler instanceof HandlerMethod hm)) {
            return true;
        }
        String ip = RequestInfo.clientIp(request);
        if (!tryConsume("global:" + ip, props.defaultLimit(), response)) {
            return false;
        }
        RateLimit rl = hm.getMethodAnnotation(RateLimit.class);
        if (rl == null) {
            rl = hm.getBeanType().getAnnotation(RateLimit.class);
        }
        if (rl == null) {
            return true;
        }
        String name = rl.name().isEmpty() ? hm.getBeanType().getSimpleName() + "." + hm.getMethod().getName()
                : rl.name();
        String who = switch (rl.key()) {
            case IP -> "ip:" + ip;
            case CALLER -> "u:" + Callers.current().map(c -> c.username()).orElse("-anonymous-");
            case CALLER_OR_IP -> Callers.current().map(c -> "u:" + c.username()).orElse("ip:" + ip);
        };
        return tryConsume("route:" + name + ":" + who, rl.value(), response);
    }

    private boolean tryConsume(String key, String spec, HttpServletResponse response) throws Exception {
        ConsumptionProbe probe;
        try {
            probe = buckets.builder()
                    .build(("rl:" + key).getBytes(StandardCharsets.UTF_8),
                            configs.computeIfAbsent(spec, RateLimitInterceptor::config))
                    .tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            log.warn("rate limiter unavailable, failing open: {}", e.getMessage());
            return true;
        }
        if (probe.isConsumed()) {
            return true;
        }
        long retryAfter = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(),
                new ErrorEnvelope(ErrorCodes.RATE_LIMITED, "Rate limit exceeded: " + spec,
                        Map.of("retry_after_seconds", retryAfter)));
        return false;
    }

    static Supplier<BucketConfiguration> config(String spec) {
        String[] parts = spec.trim().split("/");
        long count = Long.parseLong(parts[0].trim());
        Duration period = switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
            case "second", "s" -> Duration.ofSeconds(1);
            case "minute", "min", "m" -> Duration.ofMinutes(1);
            case "hour", "h" -> Duration.ofHours(1);
            case "day", "d" -> Duration.ofDays(1);
            default -> throw new IllegalArgumentException("bad rate limit period in '" + spec + "'");
        };
        BucketConfiguration cfg = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(count).refillGreedy(count, period).build())
                .build();
        return () -> cfg;
    }
}
