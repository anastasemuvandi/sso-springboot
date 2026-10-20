package rw.gov.risa.sso.wrapper.audit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Spec §5.1/§7.8: events are ACTUALLY written to the audit service, and parked (not lost) when it is down. */
class HttpAuditEmitterTest {

    WireMockServer audit;
    StringRedisTemplate redis;
    ListOperations<String, String> list;
    HttpAuditEmitter emitter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        audit = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        audit.start();
        redis = mock(StringRedisTemplate.class);
        list = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(list);
        emitter = new HttpAuditEmitter(new AuditProperties(audit.baseUrl(), "k-123", Duration.ofSeconds(2)), redis,
                new ObjectMapper(), Runnable::run);
    }

    @AfterEach
    void tearDown() {
        audit.stop();
    }

    @Test
    void loginEventIsPostedWithApiKeyAndMappedType() {
        audit.stubFor(post("/audit/events").willReturn(aResponse().withStatus(201)));
        emitter.emit(AuditEvent.of(AuditEventType.LOGIN_DENIED).denied().actor("jdoe").clientId("minagri-web")
                .requestIp("10.0.0.1").put("reason", "access_denied").institution("minagri"));
        audit.verify(postRequestedFor(urlEqualTo("/audit/events"))
                .withHeader("X-Audit-API-Key", equalTo("k-123"))
                .withRequestBody(equalToJson("""
                        {"event_type":"authz.access.denied","user_id":"jdoe","app_id":"minagri-web",
                         "performed_by":"jdoe","ip_address":"10.0.0.1","institution":"minagri","target":null,
                         "severity":"WARNING",
                         "details":{"outcome":"denied","session":null,"reason":"access_denied","institution":"minagri"}}
                        """)));
        verify(list, never()).rightPush(anyString(), anyString());
    }

    @Test
    void auditOutageParksTheEventInsteadOfThrowing() {
        audit.stubFor(post("/audit/events").willReturn(aResponse().withStatus(503)));
        emitter.emit(AuditEvent.of(AuditEventType.LOGIN_SUCCESS).actor("jdoe"));
        verify(list).rightPush(eq(HttpAuditEmitter.OUTBOX), anyString());
    }
}
