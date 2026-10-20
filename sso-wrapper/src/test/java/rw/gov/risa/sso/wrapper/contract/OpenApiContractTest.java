package rw.gov.risa.sso.wrapper.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/**
 * The committed {@code sso-wrapper/openapi.json} must equal what the app serves. Regenerate with
 * {@code mvn -f sso-wrapper/pom.xml test -Dtest=OpenApiContractTest -Dopenapi.update=true}.
 */
@WrapperTest
class OpenApiContractTest {

    static final Path COMMITTED = Path.of("openapi.json");

    @Autowired MockMvc mvc;
    @MockitoBean AuditEmitter audit;

    @Test
    void committedContractIsCurrent() throws Exception {
        String served = mvc.perform(get("/v3/api-docs")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        JsonNode servedTree = om.readTree(served);
        // The server URL depends on the test port; it is not part of the contract.
        ((com.fasterxml.jackson.databind.node.ObjectNode) servedTree).remove("servers");
        String normalised = om.writeValueAsString(om.treeToValue(servedTree, Object.class)) + "\n";
        if (Boolean.getBoolean("openapi.update") || !Files.exists(COMMITTED)) {
            Files.writeString(COMMITTED, normalised, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(COMMITTED, StandardCharsets.UTF_8))
                .as("sso-wrapper/openapi.json is stale — rerun with -Dopenapi.update=true and commit it")
                .isEqualTo(normalised);
    }
}
