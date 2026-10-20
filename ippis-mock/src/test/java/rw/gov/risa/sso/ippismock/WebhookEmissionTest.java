package rw.gov.risa.sso.ippismock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import rw.gov.risa.sso.ippismock.store.EmployeeStore;
import rw.gov.risa.sso.ippismock.webhook.WebhookEmitter;

/** Writes emit a signed IPPIS event to the SSO; delivery failures never fail the write. */
@SpringBootTest
@AutoConfigureMockMvc
class WebhookEmissionTest {
    static final WireMockServer SSO = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        SSO.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("ippis-mock.webhook.url", () -> SSO.baseUrl() + "/ippis/events");
        r.add("ippis-mock.webhook.secret", () -> "s3cr3t");
    }

    @AfterAll
    static void stop() {
        SSO.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EmployeeStore store;

    @BeforeEach
    void reset() {
        store.reset();
        SSO.resetAll();
        SSO.stubFor(post(urlEqualTo("/ippis/events")).willReturn(aResponse().withStatus(200)));
    }

    private Map<String, Object> event(LoggedRequest req) throws Exception {
        return mapper.readValue(req.getBodyAsString(), new TypeReference<>() { });
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeEmitsSignedEvent() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/employees").header("X-IPPIS-API-Key", "change-me-in-ippis")
                .contentType(MediaType.APPLICATION_JSON).content("{\"employee_number\":\"EMP960\"}"))
                .andExpect(status().isCreated());
        mvc.perform(MockMvcRequestBuilders.delete("/employees/EMP960").header("X-IPPIS-API-Key", "change-me-in-ippis"))
                .andExpect(status().isOk());

        List<LoggedRequest> requests = SSO.findAll(postRequestedFor(urlEqualTo("/ippis/events")));
        assertThat(requests).hasSize(2);
        assertThat(event(requests.get(0)).get("event")).isEqualTo("employee.updated");
        Map<String, Object> second = event(requests.get(1));
        assertThat(second.get("event")).isEqualTo("employee.terminated");
        assertThat(((Map<String, Object>) second.get("employee")).get("employee_number")).isEqualTo("EMP960");

        for (LoggedRequest req : requests) {
            String ts = req.getHeader(WebhookEmitter.TIMESTAMP_HEADER);
            assertThat(Long.parseLong(ts)).isCloseTo(Instant.now().getEpochSecond(), org.assertj.core.data.Offset.offset(60L));
            byte[] signed = (ts + req.getBodyAsString()).getBytes(StandardCharsets.UTF_8);
            assertThat(req.getHeader(WebhookEmitter.SIGNATURE_HEADER))
                    .isEqualTo(WebhookEmitter.sign("s3cr3t", signed))
                    .startsWith("sha256=");
            assertThat(req.getHeader(WebhookEmitter.EVENT_ID_HEADER)).isNotBlank();
        }
        assertThat(requests.get(0).getHeader(WebhookEmitter.EVENT_ID_HEADER))
                .isNotEqualTo(requests.get(1).getHeader(WebhookEmitter.EVENT_ID_HEADER));
        SSO.verify(postRequestedFor(urlEqualTo("/ippis/events")).withHeader("Content-Type", equalTo("application/json")));
    }

    @Test
    void deliveryFailureNeverFailsTheWrite() throws Exception {
        SSO.resetAll();
        SSO.stubFor(post(urlEqualTo("/ippis/events")).willReturn(aResponse().withStatus(500)));
        mvc.perform(MockMvcRequestBuilders.patch("/employees/EMP001").header("X-IPPIS-API-Key", "change-me-in-ippis")
                .contentType(MediaType.APPLICATION_JSON).content("{\"position\":\"x\"}"))
                .andExpect(status().isOk());
        SSO.stop();
        try {
            mvc.perform(MockMvcRequestBuilders.patch("/employees/EMP001").header("X-IPPIS-API-Key", "change-me-in-ippis")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"position\":\"y\"}"))
                    .andExpect(status().isOk());
        } finally {
            SSO.start();
        }
    }
}
