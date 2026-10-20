package rw.gov.risa.sso.wrapper.ratelimit;

import java.time.Duration;

import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;

@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor interceptor;

    public RateLimitConfig(@org.springframework.context.annotation.Lazy RateLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Bean(destroyMethod = "shutdown")
    RedisClient bucket4jRedisClient(RedisProperties redis) {
        RedisURI.Builder uri = RedisURI.builder().withHost(redis.getHost()).withPort(redis.getPort())
                .withTimeout(Duration.ofSeconds(2));
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            uri.withPassword(redis.getPassword().toCharArray());
        }
        return RedisClient.create(uri.build());
    }

    /** Connects lazily so the app boots (and fails open) while Redis is down. */
    @Bean
    ProxyManager<byte[]> rateLimitBuckets(RedisClient client) {
        return new LazyLettuceProxyManager(() -> {
            StatefulRedisConnection<byte[], byte[]> conn = client.connect(ByteArrayCodec.INSTANCE);
            return LettuceBasedProxyManager.builderFor(conn)
                    .withExpirationStrategy(
                            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(5)))
                    .build();
        });
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/**");
    }
}
