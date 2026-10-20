package rw.gov.risa.sso.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.jwk.JWKSet;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spring Session Data Redis for real: the session (and the tokens in it) lives in Redis, the browser
 * holds only an opaque HttpOnly cookie, and a SECOND BFF instance serves the same session.
 */
@Tag("it")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RedisSessionIT {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("bff.wrapper-url", BffTestSupport.WRAPPER::baseUrl);
        r.add("bff.wrapper-public-url", () -> BffTestSupport.PUBLIC_WRAPPER);
        r.add("bff.client-secret", () -> BffTestSupport.SECRET);
        r.add("bff.spa-dir", BffTestSupport.SPA_DIR::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;

    @Test
    void sessionsLiveInRedisAndAreSharedAcrossInstances() throws Exception {
        BffTestSupport.WRAPPER.resetAll();
        BffTestSupport.WRAPPER.stubFor(WireMock.get(urlEqualTo("/oauth2/jwks")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(new JWKSet(BffTestSupport.KEY.toPublicJWK()).toString())));

        LoginFlow flow = new LoginFlow(mvc);
        assertThat(flow.login("/clients/abc", List.of("super_admin"))).isEqualTo("/clients/abc");
        assertThat(flow.session).as("no container session: Spring Session owns it").isNull();

        Cookie sessionCookie = flow.cookies.get("GORSSO_BFF_SESSION");
        assertThat(sessionCookie).isNotNull();
        assertThat(sessionCookie.isHttpOnly()).isTrue();
        assertThat(sessionCookie.getSecure()).isTrue();
        assertThat(sessionCookie.getValue()).doesNotContain("eyJ");

        Set<String> keys = redis.keys("gorsso:bff:session:sessions:*");
        assertThat(keys).isNotEmpty();

        assertThat(flow.perform(get("/bff/session")).getResponse().getContentAsString()).contains("\"alice\"");

        // A second instance, same Redis: the cookie alone is enough.
        try (ConfigurableApplicationContext second = new SpringApplicationBuilder(ConsoleBffApplication.class)
                .run("--server.port=0",
                        "--spring.data.redis.url=redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379),
                        "--bff.wrapper-url=" + BffTestSupport.WRAPPER.baseUrl(),
                        "--bff.wrapper-public-url=" + BffTestSupport.PUBLIC_WRAPPER,
                        "--bff.client-secret=" + BffTestSupport.SECRET)) {
            int port = Integer.parseInt(second.getEnvironment().getProperty("local.server.port"));
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> me = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/bff/session"))
                    .header("Cookie", "GORSSO_BFF_SESSION=" + sessionCookie.getValue()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(me.body()).contains("\"username\":\"alice\"");

            BffTestSupport.WRAPPER.stubFor(WireMock.get(urlEqualTo("/users"))
                    .withHeader("Authorization", WireMock.matching("Bearer eyJ.*"))
                    .willReturn(aResponse().withBody("[]")));
            HttpResponse<String> relayed = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/users"))
                    .header("Cookie", "GORSSO_BFF_SESSION=" + sessionCookie.getValue()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(relayed.statusCode()).isEqualTo(200);
            BffTestSupport.WRAPPER.verify(WireMock.getRequestedFor(urlEqualTo("/users"))
                    .withHeader("Cookie", WireMock.absent()));

            // Logging out on instance 1 ends the session everywhere.
            flow.perform(get("/"));
            assertThat(flow.perform(post("/bff/logout").header("Accept", "application/json")
                    .header("X-XSRF-TOKEN", flow.xsrf().getValue())).getResponse().getStatus()).isEqualTo(200);
            HttpResponse<String> after = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/bff/session"))
                    .header("Cookie", "GORSSO_BFF_SESSION=" + sessionCookie.getValue()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(after.statusCode()).isEqualTo(401);
        }
        BffTestSupport.WRAPPER.verify(WireMock.postRequestedFor(urlEqualTo("/oauth2/token"))
                .withRequestBody(WireMock.containing("client_secret=" + BffTestSupport.SECRET))
                .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded;charset=UTF-8")));
    }
}
