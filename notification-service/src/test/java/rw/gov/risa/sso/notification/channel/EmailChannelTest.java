package rw.gov.risa.sso.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import rw.gov.risa.sso.notification.service.TestProps;

class EmailChannelTest {

    private static AlertMessage msg() {
        return new AlertMessage(null, "brute_force", "CRITICAL", "t", "m", "alice", null, null, Map.of());
    }

    @Test
    void notConfiguredIsASupportedStateRecordedAsSkipped() {
        EmailChannel ch = new EmailChannel(TestProps.props("a@gov.rw", "", ""), new TemplateEngine());
        assertThat(ch.configured()).isFalse();
        assertThat(ch.deliver(msg(), List.of("a@gov.rw"))).singleElement().satisfies(d -> {
            assertThat(d.status()).isEqualTo("skipped");
            assertThat(d.recipient()).isEqualTo("a@gov.rw");
        });
    }

    @Test
    void noRecipientsIsSkipped() {
        EmailChannel ch = new EmailChannel(TestProps.props("", "smtp.example", ""), new TemplateEngine());
        assertThat(ch.deliver(msg(), List.of())).singleElement().extracting(Delivery::status).isEqualTo("skipped");
    }

    @Test
    void unreachableSmtpIsFailedNotThrown() {
        var base = TestProps.props("a@gov.rw", "", "");
        var props = new rw.gov.risa.sso.notification.config.NotificationProperties("key", false, "a@gov.rw", "", 2, 6,
                22, 5, 60, base.audit(),
                new rw.gov.risa.sso.notification.config.NotificationProperties.Smtp("127.0.0.1", 1, "", "",
                        "sso-alerts@gov.rw", false, 2),
                base.detector(), base.report());
        var engine = new org.thymeleaf.spring6.SpringTemplateEngine();
        engine.setTemplateResolver(new org.thymeleaf.templateresolver.StringTemplateResolver());
        EmailChannel ch = new EmailChannel(props, engine);
        Delivery d = ch.send("s", "<p>body</p>", new org.thymeleaf.context.Context(), List.of("a@gov.rw"));
        assertThat(d.status()).isEqualTo("failed");
        assertThat(d.error()).isNotBlank();
    }
}
