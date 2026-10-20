package rw.gov.risa.sso.wrapper.service.setuplink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.setuplink.SetupLinkPolicy;
import rw.gov.risa.sso.wrapper.service.ItServices;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.mail.MailDeliveryLog;
import rw.gov.risa.sso.wrapper.service.mail.MailSettings;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;
import rw.gov.risa.sso.wrapper.support.RedisIT;

@Tag("it")
class SetupLinkServiceIT {

    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP);

    static ItServices s;
    static SetupLinkService links;
    static MailDeliveryLog deliveries;

    @BeforeAll
    static void up() {
        KeycloakIT.container();
        s = new ItServices();
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("localhost");
        sender.setPort(ServerSetupTest.SMTP.getPort());
        sender.setJavaMailProperties(new Properties());
        var provider = new StaticListableBeanFactory(Map.of("mail", sender)).getBeanProvider(JavaMailSender.class);
        deliveries = new MailDeliveryLog(RedisIT.template());
        Mailer mailer = new Mailer(provider, new MailSettings("sso@it.gov.rw", "http://portal", true), "localhost",
                deliveries);
        links = new SetupLinkService(KeycloakIT.executors(), new KeycloakDirectory(),
                new RedisSetupLinkStore(RedisIT.template(), deliveries), mailer, s.access, KeycloakIT.ssoProperties(),
                s.audit, RedisIT.template());
    }

    /** A provisioned-style account: system password + UPDATE_PASSWORD pending. */
    private static String pendingUser(String username) {
        String uid = KeycloakIT.createUser(username, "minagri");
        UserRepresentation u = KeycloakIT.realm().users().get(uid).toRepresentation();
        u.setRequiredActions(List.of(SetupLinkPolicy.UPDATE_PASSWORD));
        KeycloakIT.realm().users().get(uid).update(u);
        return uid;
    }

    private static String token(String url) {
        return url.substring(url.indexOf("token=") + 6);
    }

    @Test
    void oneLiveLinkGetDoesNotSpendRefusedPasswordDoesNotSpendSuccessSpends() {
        String client = KeycloakIT.unique("minagri-setup");
        KeycloakIT.createClient(client);
        String uid = pendingUser(KeycloakIT.unique("setup-a"));
        s.access.admitUser(s.access.serviceAccount(), client, uid);

        String old = token(links.issueUrl(uid, List.of(client)));
        String tok = token(links.issueUrl(uid, List.of()));
        assertThat(links.open(old).refusal()).isEqualTo(SetupLinkPolicy.LINK_UNKNOWN);
        assertThat(RedisIT.template().keys("setup-token:*")).noneMatch(k -> k.contains(tok));

        assertThat(links.open(tok).usable()).isTrue();
        assertThat(links.open(tok).usable()).isTrue();

        var weak = links.complete(tok, "short", "short", null);
        assertThat(weak.outcome()).isEqualTo(SetupLinkPolicy.PASSWORD_REJECTED);
        assertThat(weak.message()).isNotBlank();
        assertThat(links.open(tok).usable()).isTrue();

        var done = links.complete(tok, "Strong-Passw0rd", "Strong-Passw0rd", "127.0.0.1");
        assertThat(done.completed()).isTrue();
        assertThat(done.applications()).extracting(SetupLinkService.AppLink::clientId).containsExactly(client);
        assertThat(done.applications().get(0).url()).isEqualTo("http://localhost:9999/home");
        assertThat(KeycloakIT.realm().users().get(uid).toRepresentation().getRequiredActions()).isEmpty();
        assertThat(links.open(tok).usable()).isFalse();
        assertThat(KeycloakIT.tokenResponse(KeycloakIT.TEST_CLIENT, KeycloakIT.realm().users().get(uid)
                .toRepresentation().getUsername(), "Strong-Passw0rd")).containsKey("access_token");
    }

    @Test
    void setPasswordRefusalIs400WithKeycloaksMessage() {
        String uid = pendingUser(KeycloakIT.unique("setup-b"));
        assertThatThrownBy(() -> links.setPassword(uid, "abc"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).error()).isEqualTo("password_rejected");
                    assertThat(((ApiException) e).status().value()).isEqualTo(400);
                });
    }

    @Test
    void holderResendIsThrottledAndAdminResendOnlyAfterAFailure() throws Exception {
        String username = KeycloakIT.unique("setup-c");
        String uid = pendingUser(username);

        assertThat(links.requestResend("nobody-" + username, null)).isEqualTo(SetupLinkPolicy.NO_ACCOUNT);
        assertThat(links.requestResend(username, null)).isEqualTo(SetupLinkPolicy.SENT);
        assertThat(smtp.getReceivedMessages()).anySatisfy(m ->
                assertThat(m.getSubject()).isEqualTo(Mailer.SUBJECT_SETUP_LINK));
        assertThat(links.requestResend(username + "@it.gov.rw", null)).isEqualTo(SetupLinkPolicy.COOLDOWN);
        assertThat(deliveries.last(uid).orElseThrow().via()).isEqualTo(SetupLinkPolicy.VIA_HOLDER);

        // Last email went out → the admin may not resend.
        assertThat(links.adminResend(uid, "minagri.admin", "minagri", null).outcome())
                .isEqualTo(SetupLinkPolicy.LAST_EMAIL_DELIVERED);
        links.recordDelivery(uid, SetupLinkPolicy.DELIVERY_FAILED, SetupLinkPolicy.VIA_PROVISIONING);
        assertThat(links.adminResend(uid, "minagri.admin", "minagri", null).sent()).isTrue();
        assertThat(deliveries.last(uid).orElseThrow().via()).isEqualTo(SetupLinkPolicy.VIA_ADMIN);
    }
}
