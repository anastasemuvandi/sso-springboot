package rw.gov.risa.sso.audit.config;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Scheduling + ShedLock only when workers are enabled. Locks use the database clock and have
     * a lease (lockAtMostFor), so a killed run never blocks later runs, and with several replicas
     * exactly one runs each job.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "audit.workers.enabled", havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    @EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
    static class Scheduling {

        @Bean
        LockProvider lockProvider(DataSource dataSource) {
            return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                    .withJdbcTemplate(new JdbcTemplate(dataSource))
                    .withTableName("shedlock")
                    .usingDbTime()
                    .build());
        }
    }
}
