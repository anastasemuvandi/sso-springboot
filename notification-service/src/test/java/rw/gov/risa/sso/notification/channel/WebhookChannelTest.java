package rw.gov.risa.sso.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import rw.gov.risa.sso.notification.service.TestProps;

class WebhookChannelTest {

    @Test
    void payloadIsSlackAttachments() {
        Map<String, Object> p = WebhookChannel.payload("brute_force", "CRITICAL", Map.of("count", 5));
        @SuppressWarnings("unchecked")
        Map<String, Object> a = ((List<Map<String, Object>>) p.get("attachments")).get(0);
        assertThat(a).containsEntry("color", "#cc0000")
                .containsEntry("title", ":red_circle: [CRITICAL] SSO Alert — Brute Force")
                .containsEntry("footer", "Government of Rwanda SSO");
        assertThat(a.get("fields")).isEqualTo(List.of(Map.of("title", "count", "value", "5", "short", true)));
    }

    @Test
    void urlIsRedactedBecauseItIsASecret() {
        assertThat(WebhookChannel.redact("https://hooks.slack.com/services/T000/B000/XXXX"))
                .isEqualTo("https://hooks.slack.com/…");
        assertThat(WebhookChannel.redact("http://localhost:8089/hook")).isEqualTo("http://localhost:8089/…");
    }

    @Test
    void unsetUrlIsSkipped() {
        WebhookChannel ch = new WebhookChannel(TestProps.props("", "", ""), RestClient.builder());
        List<Delivery> d = ch.deliver(new AlertMessage(null, "brute_force", "CRITICAL", "t", "m", null, null, null,
                Map.of()), List.of());
        assertThat(d).singleElement().satisfies(x -> {
            assertThat(x.status()).isEqualTo("skipped");
            assertThat(x.error()).contains("WEBHOOK_URL");
        });
    }

    @Test
    void unreachableUrlIsFailed() {
        WebhookChannel ch = new WebhookChannel(TestProps.props("", "", "http://127.0.0.1:1/hook"),
                RestClient.builder());
        List<Delivery> d = ch.deliver(new AlertMessage(null, "brute_force", "CRITICAL", "t", "m", null, null, null,
                Map.of()), List.of());
        assertThat(d).singleElement().extracting(Delivery::status).isEqualTo("failed");
    }
}
