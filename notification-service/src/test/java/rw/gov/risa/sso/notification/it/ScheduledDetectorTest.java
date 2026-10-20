package rw.gov.risa.sso.notification.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The real scheduler path: {@code @Scheduled} + ShedLock (JDBC) runs the detector on its own,
 * takes the {@code notification-detector} lock and moves the watermark.
 */
@Tag("it")
@Testcontainers
@DirtiesContext
@SpringBootTest(properties = {
        "notification.detector.interval-ms=300",
        "notification.detector.initial-delay-ms=0",
        "notification.report.enabled=false",
})
class ScheduledDetectorTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
        WIREMOCK.stubFor(get(urlPathEqualTo("/audit/events")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"total\":0,\"limit\":1000,\"offset\":0,\"items\":[]}")));
    }

    @AfterAll
    static void stop() {
        WIREMOCK.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("notification.audit.base-url", WIREMOCK::baseUrl);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void scheduledRunTakesTheShedLockAndAdvancesTheWatermark() throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        List<Map<String, Object>> locks = List.of();
        List<Map<String, Object>> state = List.of();
        while (Instant.now().isBefore(deadline)) {
            locks = jdbc.queryForList("SELECT name FROM shedlock WHERE name = 'notification-detector'");
            state = jdbc.queryForList("SELECT watermark FROM detector_state WHERE name = 'detector'");
            if (!locks.isEmpty() && !state.isEmpty()) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(locks).hasSize(1);
        assertThat(state).hasSize(1);
        assertThat(WIREMOCK.getAllServeEvents()).isNotEmpty();
    }
}
