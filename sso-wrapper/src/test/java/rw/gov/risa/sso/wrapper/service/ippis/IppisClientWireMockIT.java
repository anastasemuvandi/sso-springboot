package rw.gov.risa.sso.wrapper.service.ippis;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPersonViews;

/** The IPPIS client against WireMock: API-key header, envelopes, 404 = absent, transient retry. */
@Tag("it")
class IppisClientWireMockIT {

    WireMockServer wm;
    IppisClient client;

    static final String PERSON = """
            {"id":"EMP6001","nationalId":"1199","firstName":"Moise","employments":[
              {"employerId":"MINAGRI","jobs":[{"id":"a","positionName":"Old","startDate":"2015-01-01",
                "endDate":"2026-01-31","reasonForChange":"Removed","updatedAt":"2026-02-01T00:00:00Z"}]},
              {"employerId":"MINISANTE","jobs":[{"id":"b","positionName":"New","startDate":"2026-02-01",
                "isSecondaryEmployment":false,"updatedAt":"2026-02-02T00:00:00+02:00"}]}]}
            """;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        IppisProperties p = IppisFixtures.props("");
        IppisProperties props = new IppisProperties(wm.baseUrl(), "the-key", Duration.ofSeconds(2),
                p.employeeNumberAttribute(), 2, 3, Duration.ZERO, 100, false, p.watermarkKey(), p.auth(), p.webhook(),
                p.schedule());
        client = new IppisClient(props, RestClient.builder());
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    @Test
    void getPersonSendsTheApiKeyAndParsesTheNestedFeed() {
        wm.stubFor(get("/people/EMP6001").willReturn(okJson(PERSON)));
        var person = client.getPerson("EMP6001").orElseThrow();
        assertThat(person.employments()).hasSize(2);
        assertThat(person.employments().get(1).jobs().get(0).updatedAt()).isEqualTo(Instant.parse("2026-02-01T22:00:00Z"));
        // the mover fixture: one post closed, one open → not a leaver
        assertThat(IppisPersonViews.viewOf(person).isLeaver()).isFalse();
        wm.verify(getRequestedFor(urlEqualTo("/people/EMP6001")).withHeader("X-IPPIS-API-Key", equalTo("the-key")));
    }

    @Test
    void notFoundIsDefinitiveAndNotRetried() {
        wm.stubFor(get("/people/NOPE").willReturn(aResponse().withStatus(404)));
        assertThat(client.getPerson("NOPE")).isEmpty();
        wm.verify(1, getRequestedFor(urlEqualTo("/people/NOPE")));
    }

    @Test
    void transientFailureIsRetried() {
        wm.stubFor(get("/people/EMP1").inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        wm.stubFor(get("/people/EMP1").inScenario("flaky").whenScenarioStateIs("ok")
                .willReturn(okJson("{\"id\":\"EMP1\",\"employments\":[]}")));
        assertThat(client.getPerson("EMP1")).isPresent();
        wm.verify(2, getRequestedFor(urlEqualTo("/people/EMP1")));
    }

    @Test
    void permanentClientErrorIsNotRetried() {
        wm.stubFor(get("/people/EMP1").willReturn(aResponse().withStatus(403)));
        assertThatThrownBy(() -> client.getPerson("EMP1")).isInstanceOf(HttpClientErrorException.class);
        wm.verify(1, getRequestedFor(urlEqualTo("/people/EMP1")));
    }

    @Test
    void deltaPassesTheWatermarkAndReadsThePeopleEnvelope() {
        wm.stubFor(get(urlPathEqualTo("/people")).willReturn(okJson("{\"people\":[" + PERSON + "],\"total\":1}")));
        assertThat(client.listPeopleChangedSince(Instant.parse("2026-01-01T00:00:00Z"))).hasSize(1);
        wm.verify(getRequestedFor(urlPathEqualTo("/people"))
                .withQueryParam("changed_since", equalTo("2026-01-01T00:00:00Z")));
    }
}
