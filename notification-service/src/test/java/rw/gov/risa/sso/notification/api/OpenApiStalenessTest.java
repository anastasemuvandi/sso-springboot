package rw.gov.risa.sso.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The committed {@code notification-service/openapi.json} must equal what springdoc generates.
 * Regenerate with {@code mvn -f notification-service/pom.xml test -Dtest=OpenApiStalenessTest -Dopenapi.update=true}.
 * Runs without Docker: the database is mocked out.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration",
        "notification.scheduling.enabled=false",
})
@AutoConfigureMockMvc
class OpenApiStalenessTest {

    private static final Path COMMITTED = Path.of("openapi.json");

    @MockitoBean
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Test
    void committedOpenApiIsCurrent() throws Exception {
        String raw = mvc.perform(get("/v3/api-docs")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        ObjectMapper om = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        String generated = om.writeValueAsString(om.readValue(raw, Object.class)) + "\n";
        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(COMMITTED, generated, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(COMMITTED))
                .as("openapi.json missing; run with -Dopenapi.update=true").isTrue();
        assertThat(Files.readString(COMMITTED, StandardCharsets.UTF_8))
                .as("openapi.json is stale; regenerate with -Dopenapi.update=true")
                .isEqualTo(generated);
    }
}
