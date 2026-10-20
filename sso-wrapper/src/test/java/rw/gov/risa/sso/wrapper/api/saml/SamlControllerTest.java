package rw.gov.risa.sso.wrapper.api.saml;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.service.gateway.GatewayProperties;
import rw.gov.risa.sso.wrapper.service.gateway.KeycloakOidc;
import rw.gov.risa.sso.wrapper.service.gateway.SamlGatewayService;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/** {@code GET /saml/metadata}: 404 unless ENABLE_SAML; when on, Keycloak's descriptor is proxied. */
@WrapperTest
class SamlControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuditEmitter audit;

    @Test
    void disabledByDefault() throws Exception {
        mvc.perform(get("/saml/metadata")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("SAML support is disabled. Set ENABLE_SAML=True to enable."));
    }

    @Test
    void enabledProxiesTheDescriptor() {
        WireMockServer kc = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        kc.start();
        try {
            kc.stubFor(WireMock.get(urlEqualTo("/realms/government-internal/protocol/saml/descriptor"))
                    .willReturn(aResponse().withHeader("Content-Type", "application/xml").withBody("<md:EntityDescriptor/>")));
            SsoProperties sso = new SsoProperties(true, "test", "http://localhost:8000",
                    new SsoProperties.Keycloak(kc.baseUrl(), kc.baseUrl(), kc.baseUrl(), "government-internal",
                            "sso-client", "", List.of()), new SsoProperties.Cors(List.of()));
            GatewayProperties on = new GatewayProperties(true, Duration.ofSeconds(300), Duration.ofSeconds(2), false);
            SamlGatewayService saml = new SamlGatewayService(on, new KeycloakOidc(sso, on, new ObjectMapper()));
            assertThat(new String(saml.metadata())).isEqualTo("<md:EntityDescriptor/>");

            kc.stubFor(WireMock.get(urlEqualTo("/realms/government-internal/protocol/saml/descriptor")).atPriority(1)
                    .willReturn(aResponse().withStatus(500)));
            assertThatThrownBy(saml::metadata).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status().value()).isEqualTo(502));
        } finally {
            kc.stop();
        }
    }
}
