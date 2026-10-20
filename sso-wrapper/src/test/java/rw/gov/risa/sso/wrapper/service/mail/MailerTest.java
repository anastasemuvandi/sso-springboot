package rw.gov.risa.sso.wrapper.service.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailerTest {

    MailDeliveryLog log = mock(MailDeliveryLog.class);

    Mailer mailer(String host, JavaMailSender sender) {
        var provider = new StaticListableBeanFactory(sender == null ? Map.of() : Map.of("mail", sender))
                .getBeanProvider(JavaMailSender.class);
        return new Mailer(provider, new MailSettings("sso@gov.rw", "http://portal", true), host, log);
    }

    static Mailer.WelcomeMail welcome(String url, boolean emailSent, boolean byAdmin, List<String> apps) {
        return new Mailer.WelcomeMail("uid", "a@gov.rw", "Ada", "ada", apps, "minagri", null, emailSent, byAdmin, url, 72);
    }

    @Test
    void notConfiguredIsASupportedStateAndStillRecordsTheLinkDelivery() {
        Mailer m = mailer("", new JavaMailSenderImpl());
        assertThat(m.configured()).isFalse();
        var r = m.sendWelcome(welcome("http://w/account/setup?token=t", false, false, List.of("A")));
        assertThat(r.status()).isEqualTo(DeliveryResult.Status.NOT_CONFIGURED);
        verify(log).record("uid", "not_configured", "provisioning");
        assertThat(mailer("smtp", null).configured()).isFalse();
    }

    @Test
    void sendFailureIsReturnedNeverThrown() {
        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        when(sender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        org.mockito.Mockito.doThrow(new MailSendException("relay down")).when(sender)
                .send(org.mockito.ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));
        Mailer m = mailer("smtp.gov.rw", sender);
        var r = m.sendSetupLink(new Mailer.SetupLinkMail("uid", "a@gov.rw", "Ada", "ada", "http://x", 72), "admin_resend");
        assertThat(r.status()).isEqualTo(DeliveryResult.Status.FAILED);
        assertThat(r.report()).startsWith("failed: MailSendException");
        verify(log).record("uid", "failed", "admin_resend");
        assertThat(m.send(" ", "s", "b").detail()).isEqualTo("no recipient address");
    }

    @Test
    void welcomeWithoutLinkDoesNotRecordADelivery() {
        mailer("", null).sendWelcome(welcome(null, true, false, List.of()));
        verify(log, never()).record(anyString(), anyString(), eq("provisioning"));
    }

    @Test
    void welcomeBranchesNeverContainAPassword() {
        Mailer m = mailer("", null);
        String withLink = m.renderWelcome(welcome("http://w/account/setup?token=t", false, false, List.of("Kubaka (k)", "HMIS")));
        assertThat(withLink).contains("Hello Ada,", "Username: ada", "Sign in:  http://portal", "  - Kubaka (k)\n",
                "  - HMIS\n", "http://w/account/setup?token=t", "expires in 72 hours")
                .doesNotContain("(none yet)").doesNotContainIgnoringCase("password:");
        assertThat(m.renderWelcome(welcome(null, true, false, List.of()))).contains("(none yet)", "second email");
        assertThat(m.renderWelcome(welcome(null, false, false, List.of()))).contains("IMPORTANT");
        assertThat(m.renderWelcome(welcome(null, false, true, List.of()))).contains("already been set for you")
                .doesNotContain("IMPORTANT");
    }

    @Test
    void accessChangeAndSetupLinkBodies() {
        Mailer m = mailer("", null);
        var change = new Mailer.AccessChangeMail("a@gov.rw", null, "ada", List.of("App (app)"), "minagri", null);
        assertThat(m.renderAccessGranted(change)).contains("Hello colleague,", "has been given access to:", "  - App (app)",
                "Sign in:  http://portal");
        assertThat(m.renderAccessRevoked(change)).contains("has been removed:", "  - App (app)", "unchanged");
        assertThat(m.renderSetupLink(new Mailer.SetupLinkMail("u", "a", "Ada", "ada", "http://link", 72)))
                .contains("http://link", "no longer works");
        assertThat(Mailer.appLabel("app", "App")).isEqualTo("App (app)");
        assertThat(Mailer.appLabel("app", "app")).isEqualTo("app");
        assertThat(Mailer.personName(null, " ", "ada")).isEqualTo("ada");
        assertThat(Mailer.personName("Ada", "L", "ada")).isEqualTo("Ada L");
    }
}
