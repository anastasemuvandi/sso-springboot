package rw.gov.risa.sso.wrapper.api.ippis;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.redis.testcontainers.RedisContainer;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.service.ippis.IppisWebhookService;
import rw.gov.risa.sso.wrapper.service.lifecycle.KeycloakUserGateway;
import rw.gov.risa.sso.wrapper.service.lifecycle.UserOps;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/** Real Redis: the nonce set refuses a replay of a correctly signed, fresh event. */
@Tag("it")
@Testcontainers
@WrapperTest
class IppisWebhookRedisIT {

    @Container
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:alpine"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("sso.ippis.webhook.secret", () -> "it-secret");
    }

    @Autowired MockMvc mvc;
    @MockitoBean AuditEmitter audit;
    @MockitoBean KeycloakUserGateway gateway;

    @Test
    void replayOfASignedEventIsRefused() throws Exception {
        UserOps ops = mock(UserOps.class);
        when(gateway.open()).thenReturn(ops);
        when(ops.findByAttribute(any(), any())).thenReturn(List.of());
        byte[] body = "{\"event\":\"employee.updated\",\"employee\":{\"employee_number\":\"EMP404\"}}"
                .getBytes(StandardCharsets.UTF_8);
        String ts = Long.toString(Instant.now().getEpochSecond());
        String sig = IppisWebhookService.sign("it-secret", ts, body);

        mvc.perform(post("/ippis/events").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-IPPIS-Signature", sig).header("X-IPPIS-Timestamp", ts))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("no_user"));
        mvc.perform(post("/ippis/events").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-IPPIS-Signature", sig).header("X-IPPIS-Timestamp", ts))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("replayed"));
        // a tampered body never reaches the nonce store
        mvc.perform(post("/ippis/events").contentType(MediaType.APPLICATION_JSON).content(body.length + "x")
                        .header("X-IPPIS-Signature", sig).header("X-IPPIS-Timestamp", ts))
                .andExpect(status().isUnauthorized());
    }
}
