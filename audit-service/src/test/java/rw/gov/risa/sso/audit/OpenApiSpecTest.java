package rw.gov.risa.sso.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * The committed {@code audit-service/openapi.json} must equal what the running app serves
 * (spec §1: "the generated openapi.json is committed and CI fails when it is stale").
 *
 * <p>Regenerate after an API change:
 * {@code mvn -f audit-service/pom.xml test -Dtest=OpenApiSpecTest -Dopenapi.write=true}
 *
 * <p>The full application context starts (every controller, DTO and springdoc customiser), with
 * the DataSource mocked so this runs without Docker in the default build.
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "audit.workers.enabled=false",
    "audit.api-key=openapi-test",
    "logging.structured.format.console=",
})
@AutoConfigureMockMvc
class OpenApiSpecTest {

    static final Path COMMITTED = Path.of("openapi.json");

    @MockitoBean
    DataSource dataSource;

    @Autowired
    MockMvc mvc;

    @Test
    void committedOpenApiMatchesTheRunningApp() throws Exception {
        String served = mvc.perform(get("/openapi.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode live = om.readTree(served);
        String pretty = om.writeValueAsString(live) + "\n";

        if (Boolean.getBoolean("openapi.write")) {
            Files.writeString(COMMITTED, pretty, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(COMMITTED))
                .as("audit-service/openapi.json is missing; regenerate with -Dopenapi.write=true").isTrue();
        JsonNode committed = om.readTree(Files.readString(COMMITTED, StandardCharsets.UTF_8));
        assertThat(committed)
                .as("audit-service/openapi.json is stale; regenerate with "
                        + "mvn -f audit-service/pom.xml test -Dtest=OpenApiSpecTest -Dopenapi.write=true")
                .isEqualTo(live);
    }

    @Test
    void everyAuditRouteDeclaresTheApiKeyAndHealthDoesNot() throws Exception {
        JsonNode api = new ObjectMapper().readTree(mvc.perform(get("/openapi.json"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(api.at("/components/securitySchemes/AuditApiKey/name").asText()).isEqualTo("X-Audit-API-Key");
        api.get("paths").fields().forEachRemaining(p -> p.getValue().fields().forEachRemaining(op -> {
            boolean secured = op.getValue().has("security");
            assertThat(secured).as(op.getKey() + " " + p.getKey()).isEqualTo(p.getKey().startsWith("/audit"));
        }));
        assertThat(api.at("/components/schemas/AuditEventCreate/properties/event_type").isMissingNode()).isFalse();
        assertThat(api.at("/components/schemas/IntegrityReport/properties/first_tampered_id").isMissingNode())
                .isFalse();
    }
}
