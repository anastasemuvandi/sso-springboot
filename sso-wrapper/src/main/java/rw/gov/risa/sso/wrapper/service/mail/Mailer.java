package rw.gov.risa.sso.wrapper.service.mail;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import rw.gov.risa.sso.wrapper.policy.setuplink.SetupLinkPolicy;

/**
 * The wrapper's own outbound mail (port of {@code core/mailer.py}): welcome (username, app list, the
 * wrapper's set-password link), access granted, access revoked, set-password link resend. Plain text,
 * rendered from {@code templates/mail/*.txt} (Thymeleaf TEXT mode).
 *
 * <ul>
 *   <li><b>Never throws into the caller</b>: every send returns a {@link DeliveryResult}.</li>
 *   <li><b>"Not configured" is a supported state</b>: empty {@code SMTP_HOST} → {@code NOT_CONFIGURED},
 *       nothing is rendered or sent.</li>
 *   <li>Never logs secrets (no password, token or link is logged; failures log the class + message).</li>
 *   <li>Messages that carry a set-password link ({@link #sendWelcome} with a link, {@link #sendSetupLink})
 *       record the per-account outcome in {@link MailDeliveryLog} ({@code setup-link:delivery:<uid>}).</li>
 * </ul>
 */
@Service
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    public static final String SUBJECT_WELCOME = "Your Government of Rwanda SSO account";
    public static final String SUBJECT_GRANTED = "New applications on your Government of Rwanda SSO account";
    public static final String SUBJECT_REVOKED = "Applications removed from your Government of Rwanda SSO account";
    public static final String SUBJECT_SETUP_LINK = "Set your Government of Rwanda SSO password";

    private final ObjectProvider<JavaMailSender> sender;
    private final MailSettings settings;
    private final String smtpHost;
    private final MailDeliveryLog deliveries;
    private final SpringTemplateEngine templates;

    public Mailer(ObjectProvider<JavaMailSender> sender, MailSettings settings,
                  @Value("${spring.mail.host:}") String smtpHost, MailDeliveryLog deliveries) {
        this.sender = sender;
        this.settings = settings;
        this.smtpHost = smtpHost == null ? "" : smtpHost.strip();
        this.deliveries = deliveries;
        this.templates = textTemplates();
    }

    static SpringTemplateEngine textTemplates() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/mail/");
        resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    // ---- messages --------------------------------------------------------------------------

    /**
     * A newly provisioned account. Never contains a password. {@code setPasswordUrl} null with
     * {@code passwordEmailSent=false} tells the reader the link could not be sent.
     */
    public record WelcomeMail(String userId, String to, String name, String username, List<String> applications,
                              String institution, String loginUrl, boolean passwordEmailSent,
                              boolean passwordSetByAdmin, String setPasswordUrl, int lifespanHours) {
    }

    /** Access granted to / revoked from an EXISTING account (one email per person per request). */
    public record AccessChangeMail(String to, String name, String username, List<String> applications,
                                   String institution, String loginUrl) {
    }

    /** A (re)sent set-password link. */
    public record SetupLinkMail(String userId, String to, String name, String username, String setPasswordUrl,
                                int lifespanHours) {
    }

    // ---- API -------------------------------------------------------------------------------

    /** True when there is a relay to send through (SMTP_HOST set). */
    public boolean configured() {
        return !smtpHost.isEmpty() && sender.getIfAvailable() != null;
    }

    public MailSettings settings() {
        return settings;
    }

    /** Send one plain-text message. Never throws. */
    public DeliveryResult send(String to, String subject, String body) {
        if (!configured()) {
            return DeliveryResult.notConfigured();
        }
        String recipient = to == null ? "" : to.strip();
        if (recipient.isEmpty()) {
            return DeliveryResult.failed("no recipient address");
        }
        try {
            JavaMailSender mail = sender.getObject();
            MimeMessage message = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(settings.from());
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(body, false);
            mail.send(message);
            return DeliveryResult.sent();
        } catch (Exception e) {
            String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("mailer: could not send '{}' to {} via {}: {}", subject, recipient, smtpHost, detail);
            return DeliveryResult.failed(detail);
        }
    }

    /**
     * Welcome a newly created account. When it carries a set-password link and {@code userId} is set, the
     * outcome is recorded as the account's delivery ({@code via=provisioning}).
     */
    public DeliveryResult sendWelcome(WelcomeMail m) {
        DeliveryResult r = configured() ? send(m.to(), SUBJECT_WELCOME, renderWelcome(m)) : DeliveryResult.notConfigured();
        if (m.setPasswordUrl() != null && m.userId() != null) {
            deliveries.record(m.userId(), r.status().wire(), SetupLinkPolicy.VIA_PROVISIONING);
        }
        return r;
    }

    public DeliveryResult sendAccessGranted(AccessChangeMail m) {
        return configured() ? send(m.to(), SUBJECT_GRANTED, renderAccessGranted(m)) : DeliveryResult.notConfigured();
    }

    public DeliveryResult sendAccessRevoked(AccessChangeMail m) {
        return configured() ? send(m.to(), SUBJECT_REVOKED, renderAccessRevoked(m)) : DeliveryResult.notConfigured();
    }

    /**
     * A set-password link (holder or admin resend). Records the outcome under {@code via}
     * ({@code holder_resend} / {@code admin_resend}) when {@code userId} is set.
     */
    public DeliveryResult sendSetupLink(SetupLinkMail m, String via) {
        DeliveryResult r = configured() ? send(m.to(), SUBJECT_SETUP_LINK, renderSetupLink(m))
                : DeliveryResult.notConfigured();
        if (m.userId() != null) {
            deliveries.record(m.userId(), r.status().wire(), via);
        }
        return r;
    }

    // ---- rendering (pure, public for tests) ------------------------------------------------

    public String renderWelcome(WelcomeMail m) {
        Context c = new Context(Locale.ENGLISH);
        c.setVariable("name", m.name());
        c.setVariable("username", m.username());
        c.setVariable("institution", m.institution());
        c.setVariable("loginUrl", m.loginUrl() != null ? m.loginUrl() : settings.loginUrl());
        c.setVariable("applications", m.applications() == null ? List.of() : m.applications());
        c.setVariable("passwordSetByAdmin", m.passwordSetByAdmin());
        c.setVariable("passwordEmailSent", m.passwordEmailSent());
        c.setVariable("setPasswordUrl", m.setPasswordUrl());
        c.setVariable("lifespanHours", m.lifespanHours() > 0 ? m.lifespanHours() : 72);
        return templates.process("welcome", c);
    }

    public String renderAccessGranted(AccessChangeMail m) {
        return templates.process("access-granted", changeContext(m));
    }

    public String renderAccessRevoked(AccessChangeMail m) {
        return templates.process("access-revoked", changeContext(m));
    }

    public String renderSetupLink(SetupLinkMail m) {
        Context c = new Context(Locale.ENGLISH);
        c.setVariable("name", m.name());
        c.setVariable("username", m.username());
        c.setVariable("setPasswordUrl", m.setPasswordUrl());
        c.setVariable("lifespanHours", m.lifespanHours() > 0 ? m.lifespanHours() : 72);
        return templates.process("setup-link-resend", c);
    }

    private Context changeContext(AccessChangeMail m) {
        Context c = new Context(Locale.ENGLISH);
        c.setVariable("name", m.name() == null || m.name().isBlank() ? "colleague" : m.name());
        c.setVariable("username", m.username() == null ? "" : m.username());
        c.setVariable("institution", m.institution());
        c.setVariable("loginUrl", m.loginUrl() != null ? m.loginUrl() : settings.loginUrl());
        c.setVariable("applications", m.applications() == null ? List.of() : m.applications());
        return c;
    }

    /** {@code "Name (client-id)"}, or the client id alone (reference {@code app_label}). */
    public static String appLabel(String clientId, String name) {
        String n = name == null ? "" : name.strip();
        return !n.isEmpty() && !n.equals(clientId) ? n + " (" + clientId + ")" : clientId;
    }

    /** The salutation: first + last name, else the username, else "colleague". */
    public static String personName(String firstName, String lastName, String username) {
        String full = String.join(" ", java.util.stream.Stream.of(firstName, lastName)
                .filter(p -> p != null && !p.isBlank()).map(String::strip).toList());
        if (!full.isEmpty()) {
            return full;
        }
        return username == null || username.isBlank() ? "colleague" : username;
    }
}
