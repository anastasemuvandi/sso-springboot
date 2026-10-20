package rw.gov.risa.sso.wrapper.service.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;

import rw.gov.risa.sso.wrapper.support.RedisIT;

@Tag("it")
class MailerIT {

    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP);

    private static Mailer mailer(String host, int port) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        var provider = new StaticListableBeanFactory(Map.of("mail", sender)).getBeanProvider(JavaMailSender.class);
        return new Mailer(provider, new MailSettings("sso@it.gov.rw", "http://portal", true), host,
                new MailDeliveryLog(RedisIT.template()));
    }

    @Test
    void welcomeIsDeliveredAndRecordedPerAccount() throws Exception {
        Mailer m = mailer("localhost", ServerSetupTest.SMTP.getPort());
        var r = m.sendWelcome(new Mailer.WelcomeMail("uid-welcome", "new.person@it.gov.rw", "New Person", "new.person",
                List.of("Kubaka (minagri-kubaka-web)"), "minagri", null, false, false,
                "http://localhost:8000/account/setup?token=abc", 72));
        assertThat(r.isSent()).isTrue();
        var msg = smtp.getReceivedMessages()[0];
        assertThat(msg.getSubject()).isEqualTo(Mailer.SUBJECT_WELCOME);
        String body = String.valueOf(msg.getContent());
        assertThat(body).contains("new.person", "Kubaka (minagri-kubaka-web)", "account/setup?token=abc",
                "expires in 72 hours");
        assertThat(new MailDeliveryLog(RedisIT.template()).last("uid-welcome").orElseThrow().status()).isEqualTo("sent");
    }

    @Test
    void unreachableRelayIsAFailureNotAnException() {
        Mailer m = mailer("localhost", 1);
        var r = m.sendAccessGranted(new Mailer.AccessChangeMail("x@it.gov.rw", "X", "x", List.of("A"), "minagri", null));
        assertThat(r.status()).isEqualTo(DeliveryResult.Status.FAILED);
        assertThat(r.report()).startsWith("failed: ");
    }
}
