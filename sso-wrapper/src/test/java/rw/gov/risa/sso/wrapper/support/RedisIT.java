package rw.gov.risa.sso.wrapper.support;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.redis.testcontainers.RedisContainer;

/**
 * Shared {@code redis:alpine} Testcontainer (one per test JVM) and a ready {@link StringRedisTemplate}.
 * Tag tests {@code @Tag("it")}. Call {@link #flush()} in {@code @BeforeEach} if you need an empty store.
 * For {@code @SpringBootTest}: {@code r.add("spring.data.redis.host", RedisIT::host); r.add("spring.data.redis.port", RedisIT::port);}
 */
public final class RedisIT {

    private static volatile RedisContainer container;
    private static volatile LettuceConnectionFactory factory;
    private static volatile StringRedisTemplate template;

    private RedisIT() {
    }

    public static synchronized RedisContainer container() {
        if (container == null) {
            RedisContainer c = new RedisContainer(RedisContainer.DEFAULT_IMAGE_NAME.withTag("alpine"));
            c.start();
            container = c;
        }
        return container;
    }

    public static String host() {
        return container().getHost();
    }

    public static int port() {
        return container().getFirstMappedPort();
    }

    public static synchronized LettuceConnectionFactory connectionFactory() {
        if (factory == null) {
            LettuceConnectionFactory f = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host(), port()));
            f.afterPropertiesSet();
            f.start();
            factory = f;
        }
        return factory;
    }

    public static synchronized StringRedisTemplate template() {
        if (template == null) {
            StringRedisTemplate t = new StringRedisTemplate(connectionFactory());
            t.afterPropertiesSet();
            template = t;
        }
        return template;
    }

    public static void flush() {
        template().execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
    }
}
