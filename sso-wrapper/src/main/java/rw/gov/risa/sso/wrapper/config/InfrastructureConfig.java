package rw.gov.risa.sso.wrapper.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/** Executors and the ShedLock provider (Redis; locks carry a lease so a killed run cannot block later runs). */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class InfrastructureConfig {

    @Bean
    LockProvider lockProvider(RedisConnectionFactory redis) {
        return new RedisLockProvider(redis, "gorsso-wrapper");
    }

    /** Fire-and-forget audit writes. Bounded; overflow runs on the caller thread rather than dropping. */
    @Bean(name = "auditExecutor")
    Executor auditExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("audit-");
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(5000);
        ex.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        ex.initialize();
        return ex;
    }
}
