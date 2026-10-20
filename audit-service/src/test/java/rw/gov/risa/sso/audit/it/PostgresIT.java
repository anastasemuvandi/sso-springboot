package rw.gov.risa.sso.audit.it;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One postgres:16-alpine for every IT class (started once per JVM), migrated by Flyway at
 * context start. Identical properties keep one cached Spring context across classes. Workers are
 * wired (so their beans and ShedLock can be exercised) but never fire on their own.
 *
 * <p>The audit table is append-only even here, so tests never assume an empty trail: they assert
 * on rows they wrote themselves and on deltas.
 */
@Tag("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "audit.api-key=" + PostgresIT.API_KEY,
    "audit.workers.enabled=true",
    "audit.workers.health-interval=PT1000H",
    "audit.workers.health-initial-delay=PT1000H",
    "audit.workers.cert-expiry-cron=-",
    "audit.workers.retention-cron=-",
    "audit.workers.access-review-cron=-",
    "audit.health.services=postgresql,redis,wrapper,ghost",
    "audit.health.urls.wrapper=http://127.0.0.1:9/actuator/health",
    "logging.structured.format.console=",
})
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
public abstract class PostgresIT {

    public static final String API_KEY = "test-api-key";

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        PG.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }
}
