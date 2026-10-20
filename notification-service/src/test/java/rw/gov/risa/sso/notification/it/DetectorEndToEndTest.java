package rw.gov.risa.sso.notification.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import rw.gov.risa.sso.notification.service.DetectionService;

/**
 * End to end, against real Postgres (Testcontainers), a WireMock audit service + webhook, and a
 * GreenMail SMTP server: audit events → detector cycle → alerts persisted → every channel's
 * delivery recorded (and actually received) → REST API; then watermark, cooldown,
 * acknowledge-re-arms, config override, read-all and the daily report.
 */
@Tag("it")
@Testcontainers
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DetectorEndToEndTest {

    static final String API_KEY = "it-notification-key";
    static final String AUDIT_KEY = "it-audit-key";
    static final String SECOPS = "secops@risa.gov.rw";
    static final String SOC = "soc@risa.gov.rw";
    /** 21:30 UTC = 23:30 in Kigali: outside business hours. */
    static final Instant T = Instant.parse("2026-10-20T21:30:00Z");
    static final AtomicReference<Instant> NOW = new AtomicReference<>(T);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static final GreenMail SMTP = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    static {
        WIREMOCK.start();
        SMTP.start();
    }

    @AfterAll
    static void stop() {
        WIREMOCK.stop();
        SMTP.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("notification.api-key", () -> API_KEY);
        r.add("notification.audit.base-url", WIREMOCK::baseUrl);
        r.add("notification.audit.api-key", () -> AUDIT_KEY);
        r.add("notification.webhook-url", () -> WIREMOCK.baseUrl() + "/hooks/T000/B000/secret");
        r.add("notification.smtp.host", () -> "127.0.0.1");
        r.add("notification.smtp.port", () -> SMTP.getSmtp().getPort());
        r.add("notification.smtp.use-tls", () -> false);
        r.add("notification.admin-emails", () -> SECOPS + ", " + SOC);
        r.add("notification.scheduling.enabled", () -> false);
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return new Clock() {
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                public Clock withZone(ZoneId zone) {
                    return this;
                }

                public Instant instant() {
                    return NOW.get();
                }
            };
        }
    }

    @Autowired
    DetectionService detection;

    @Autowired
    ObjectMapper om;

    @LocalServerPort
    int port;

    private final List<Map<String, Object>> auditTrail = new ArrayList<>();

    private RestClient api() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("X-Notification-API-Key", API_KEY).build();
    }

    private JsonNode getJson(String uri) throws Exception {
        return om.readTree(api().get().uri(uri).retrieve().body(String.class));
    }

    private static Map<String, Object> event(String type, String user, String ip, String app, Instant at) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", UUID.randomUUID().toString());
        e.put("event_type", type);
        e.put("category", "authentication");
        e.put("user_id", user);
        e.put("app_id", app);
        e.put("ip_address", ip);
        e.put("details", Map.of());
        e.put("severity", "INFO");
        e.put("created_at", at.atOffset(ZoneOffset.UTC).toString());
        return e;
    }

    /** (Re)stubs the audit service: the main window read, and the anomalous-access history read. */
    private void stubAudit() throws Exception {
        WIREMOCK.resetAll();
        List<Map<String, Object>> newestFirst = new ArrayList<>(auditTrail);
        newestFirst.sort((a, b) -> ((String) b.get("created_at")).compareTo((String) a.get("created_at")));
        WIREMOCK.stubFor(get(urlPathEqualTo("/audit/events")).atPriority(5)
                .withHeader("X-Audit-API-Key", equalTo(AUDIT_KEY))
                .withQueryParam("from_date", matching(".+"))
                .withQueryParam("to_date", matching(".+"))
                .withQueryParam("user_id", absent())
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(om.writeValueAsString(
                        Map.of("total", newestFirst.size(), "limit", 1000, "offset", 0, "items", newestFirst)))));
        // bob has exactly one successful login to the app in the whole trail → first access
        WIREMOCK.stubFor(get(urlPathEqualTo("/audit/events")).atPriority(1)
                .withHeader("X-Audit-API-Key", equalTo(AUDIT_KEY))
                .withQueryParam("user_id", equalTo("bob"))
                .withQueryParam("app_id", equalTo("minagri-kubaka-web"))
                .withQueryParam("event_type", equalTo("auth.login.success"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(om.writeValueAsString(
                        Map.of("total", 1, "limit", 2, "offset", 0, "items", List.of(auditTrail.get(5)))))));
        WIREMOCK.stubFor(post(urlPathEqualTo("/hooks/T000/B000/secret")).willReturn(aResponse().withStatus(200)));
    }

    private int alertCount() throws Exception {
        return getJson("/alerts?limit=500").size();
    }

    @Test
    void auditEventsBecomeAlertsAndEveryChannelDeliveryIsRecorded() throws Exception {
        // --- audit trail: brute force on alice; bob at night from two IPs, first time on the app
        for (int i = 0; i < 5; i++) {
            auditTrail.add(event("auth.login.failed", "alice", "10.0.0.1", "minagri-kubaka-web",
                    T.minus(Duration.ofMinutes(8 - i))));
        }
        auditTrail.add(event("auth.login.success", "bob", "41.186.0.1", "minagri-kubaka-web",
                T.minus(Duration.ofMinutes(20))));
        auditTrail.add(event("auth.login.success", "bob", "102.22.0.9", "minagri-kubaka-web",
                T.minus(Duration.ofMinutes(10))));
        stubAudit();

        // --- cycle 1
        DetectionService.RunResult r1 = detection.runOnce();
        assertThat(r1.fetched()).isEqualTo(7);
        assertThat(r1.newEvents()).isEqualTo(7);
        assertThat(r1.alertsCreated()).isEqualTo(4);
        WIREMOCK.verify(getRequestedFor(urlPathEqualTo("/audit/events"))
                .withQueryParam("from_date", equalTo(T.minus(Duration.ofMinutes(90)).toString()))
                .withQueryParam("to_date", equalTo(T.minusSeconds(2).toString())));

        JsonNode alerts = getJson("/alerts");
        Map<String, JsonNode> byRule = new LinkedHashMap<>();
        alerts.forEach(a -> byRule.put(a.get("rule_name").asText(), a));
        assertThat(byRule.keySet()).containsExactlyInAnyOrder(
                "brute_force", "unusual_hours", "impossible_travel", "anomalous_access");
        JsonNode bf = byRule.get("brute_force");
        assertThat(bf.get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(bf.get("user_id").asText()).isEqualTo("alice");
        assertThat(bf.get("details").get("count").asInt()).isEqualTo(5);
        assertThat(bf.get("acknowledged").asBoolean()).isFalse();
        assertThat(byRule.get("impossible_travel").get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(byRule.get("unusual_hours").get("details").get("local_hour").asInt()).isEqualTo(23);
        assertThat(byRule.get("anomalous_access").get("app_id").asText()).isEqualTo("minagri-kubaka-web");

        // --- delivery records for the brute-force alert: email + 2 x in-app + webhook, all sent
        JsonNode bfNotifs = getJson("/notifications?alert_id=" + bf.get("id").asText());
        Map<String, List<String>> statusByChannel = new LinkedHashMap<>();
        bfNotifs.forEach(n -> statusByChannel.computeIfAbsent(n.get("channel").asText(), k -> new ArrayList<>())
                .add(n.get("status").asText()));
        assertThat(statusByChannel).containsEntry("email", List.of("sent"))
                .containsEntry("inapp", List.of("sent", "sent"))
                .containsEntry("webhook", List.of("sent"));
        bfNotifs.forEach(n -> {
            assertThat(n.get("title").asText()).isEqualTo("[CRITICAL] SSO Alert — Brute Force");
            if (n.get("channel").asText().equals("webhook")) {
                assertThat(n.get("recipient").asText()).doesNotContain("secret"); // webhook URL is a secret
            }
        });
        assertThat(getJson("/notifications?limit=500")).hasSize(12); // 4+4 (critical) + 2+2 (in-app only)

        // --- the deliveries really happened
        MimeMessage[] mails = SMTP.getReceivedMessages();
        assertThat(mails).hasSize(4); // 2 critical alerts x 2 recipients
        assertThat(mails).extracting(MimeMessage::getSubject).contains(
                "[CRITICAL] SSO Security Alert — Brute Force", "[CRITICAL] SSO Security Alert — Impossible Travel");
        WIREMOCK.verify(2, postRequestedFor(urlPathEqualTo("/hooks/T000/B000/secret"))
                .withRequestBody(matchingJsonPath("$.attachments[0].footer", equalTo("Government of Rwanda SSO"))));

        // --- auth: no key / wrong key → 401 envelope; /health is open
        RestClient anon = RestClient.builder().baseUrl("http://localhost:" + port).build();
        HttpStatusCode noKey = anon.get().uri("/alerts").exchange((req, res) -> res.getStatusCode());
        assertThat(noKey.value()).isEqualTo(401);
        String body = anon.get().uri("/alerts").header("X-Notification-API-Key", "nope")
                .exchange((req, res) -> new String(res.getBody().readAllBytes()));
        assertThat(om.readTree(body).get("error").asText()).isEqualTo("unauthorized");
        assertThat(anon.get().uri("/health").retrieve().body(String.class)).contains("notification-service");

        // --- cycle 2: same audit trail, clock moved on → watermark: nothing re-processed
        NOW.set(T.plus(Duration.ofMinutes(1)));
        DetectionService.RunResult r2 = detection.runOnce();
        assertThat(r2.newEvents()).isZero();
        assertThat(alertCount()).isEqualTo(4);

        // --- cycle 3: a 6th failure for alice → rule matches again, but the 5-min cooldown holds
        auditTrail.add(event("auth.login.failed", "alice", "10.0.0.1", null, T.plusSeconds(90)));
        stubAudit();
        NOW.set(T.plus(Duration.ofMinutes(2)));
        DetectionService.RunResult r3 = detection.runOnce();
        assertThat(r3.newEvents()).isEqualTo(1);
        assertThat(r3.detections()).isEqualTo(1);
        assertThat(r3.alertsCreated()).isZero();
        assertThat(alertCount()).isEqualTo(4);

        // --- acknowledge re-arms the rule: the next failure alerts again (count 7)
        JsonNode acked = om.readTree(api().put().uri("/alerts/" + bf.get("id").asText() + "/acknowledge")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("acknowledged_by", "secops.analyst"))
                .retrieve().body(String.class));
        assertThat(acked.get("acknowledged").asBoolean()).isTrue();
        assertThat(acked.get("acknowledged_by").asText()).isEqualTo("secops.analyst");
        assertThat(acked.get("acknowledged_at").isNull()).isFalse();
        auditTrail.add(event("auth.login.failed", "alice", "10.0.0.1", null, T.plusSeconds(150)));
        stubAudit();
        NOW.set(T.plus(Duration.ofMinutes(3)));
        assertThat(detection.runOnce().alertsCreated()).isEqualTo(1);
        JsonNode newest = getJson("/alerts?rule_name=brute_force&acknowledged=false");
        assertThat(newest).hasSize(1);
        assertThat(newest.get(0).get("details").get("count").asInt()).isEqualTo(6);

        // --- config override: disable brute_force → acknowledged + new failure → still no alert
        api().put().uri("/alerts/" + newest.get(0).get("id").asText() + "/acknowledge")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("acknowledged_by", "x")).retrieve().toBodilessEntity();
        JsonNode cfg = om.readTree(api().put().uri("/config/brute_force").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", false)).retrieve().body(String.class));
        assertThat(cfg.get("enabled").asBoolean()).isFalse();
        assertThat(cfg.get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(cfg.get("id").isNull()).isFalse();
        JsonNode all = getJson("/config");
        assertThat(all).hasSize(6);
        assertThat(all.get(0).get("rule_name").asText()).isEqualTo("brute_force");
        assertThat(all.get(0).get("enabled").asBoolean()).isFalse();
        auditTrail.add(event("auth.login.failed", "alice", "10.0.0.1", null, T.plusSeconds(210)));
        stubAudit();
        NOW.set(T.plus(Duration.ofMinutes(4)));
        assertThat(detection.runOnce().alertsCreated()).isZero();
        assertThat(alertCount()).isEqualTo(5);
        HttpStatusCode unknownRule = api().put().uri("/config/nope").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", false)).exchange((req, res) -> res.getStatusCode());
        assertThat(unknownRule.value()).isEqualTo(404);

        // --- notifications: read one, read-all for a recipient
        JsonNode inbox = getJson("/notifications?recipient=" + SECOPS + "&is_read=false");
        assertThat(inbox).hasSize(5); // one in-app row per alert for this admin
        JsonNode one = om.readTree(api().put().uri("/notifications/" + inbox.get(0).get("id").asText() + "/read")
                .retrieve().body(String.class));
        assertThat(one.get("is_read").asBoolean()).isTrue();
        JsonNode marked = om.readTree(api().put().uri("/notifications/read-all?recipient=" + SECOPS)
                .retrieve().body(String.class));
        assertThat(marked.get("marked_read").asInt()).isEqualTo(4);
        assertThat(getJson("/notifications?recipient=" + SECOPS + "&is_read=false")).isEmpty();
        assertThat(getJson("/notifications?recipient=" + SOC + "&is_read=false")).hasSize(5);

        // --- daily report: JSON summary and the email
        JsonNode report = getJson("/reports/daily");
        assertThat(report.get("total").asInt()).isEqualTo(5);
        assertThat(report.get("critical").asInt()).isEqualTo(3);
        assertThat(report.get("acknowledged").asInt()).isEqualTo(2);
        assertThat(report.get("by_rule").get("brute_force").asInt()).isEqualTo(2);
        JsonNode sent = om.readTree(api().post().uri("/reports/daily/send").retrieve().body(String.class));
        assertThat(sent.get("status").asText()).isEqualTo("sent");
        assertThat(SMTP.getReceivedMessages()).extracting(MimeMessage::getSubject)
                .contains("[Daily Report] SSO Security Alerts — 2026-10-20");
    }
}
