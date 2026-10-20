package rw.gov.risa.sso.notification.channel;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import rw.gov.risa.sso.notification.config.NotificationProperties;
import rw.gov.risa.sso.notification.detector.AlertText;

/**
 * SMTP email via Spring Mail + Thymeleaf ({@code templates/email/alert.html}). "Not
 * configured" ({@code SMTP_HOST} empty) is a supported state: the delivery is recorded as
 * {@code skipped} (the reference returned silently and recorded the email as {@code sent}).
 * Sending never throws into the caller.
 */
@Component
public class EmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);
    static final String NOT_CONFIGURED = "email channel not configured (SMTP_HOST is empty)";

    private final NotificationProperties.Smtp smtp;
    private final TemplateEngine templates;
    private final JavaMailSender sender;

    public EmailChannel(NotificationProperties props, TemplateEngine templates) {
        this.smtp = props.smtp();
        this.templates = templates;
        this.sender = smtp.configured() ? buildSender(smtp) : null;
    }

    @Override
    public String name() {
        return "email";
    }

    public boolean configured() {
        return sender != null;
    }

    @Override
    public List<Delivery> deliver(AlertMessage alert, List<String> adminEmails) {
        Context ctx = new Context();
        ctx.setVariable("ruleTitle", AlertText.humanize(alert.ruleName()));
        ctx.setVariable("severity", alert.severity());
        ctx.setVariable("severityColor", severityColor(alert.severity()));
        ctx.setVariable("userId", alert.userId());
        ctx.setVariable("ipAddress", alert.ipAddress());
        ctx.setVariable("appId", alert.appId());
        ctx.setVariable("details", alert.details() == null ? Map.of() : alert.details());
        ctx.setVariable("alertId", alert.alertId());
        return List.of(send(AlertText.emailSubject(alert.severity(), alert.ruleName()), "email/alert", ctx,
                adminEmails));
    }

    /** Render {@code template} and send it to {@code recipients}; one {@link Delivery} for the batch. */
    public Delivery send(String subject, String template, Context ctx, List<String> recipients) {
        String recipient = String.join(", ", recipients);
        if (sender == null) {
            return Delivery.skipped(name(), recipient, NOT_CONFIGURED);
        }
        if (recipients.isEmpty()) {
            return Delivery.skipped(name(), "", "no email recipients configured (ADMIN_EMAILS is empty)");
        }
        try {
            String html = templates.process(template, ctx);
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, false, StandardCharsets.UTF_8.name());
            h.setFrom(smtp.from());
            h.setTo(recipients.toArray(String[]::new));
            h.setSubject(subject);
            h.setText(html, true);
            sender.send(msg);
            log.info("Email sent to {} recipient(s): {}", recipients.size(), subject);
            return Delivery.sent(name(), recipient);
        } catch (Exception e) {
            log.error("Email delivery failed: {}", e.toString());
            return Delivery.failed(name(), recipient, e);
        }
    }

    static String severityColor(String severity) {
        return switch (severity == null ? "" : severity) {
            case "CRITICAL" -> "#cc0000";
            case "WARNING" -> "#e07000";
            case "INFO" -> "#0070cc";
            default -> "#333333";
        };
    }

    private static JavaMailSender buildSender(NotificationProperties.Smtp smtp) {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(smtp.host());
        s.setPort(smtp.port());
        if (smtp.user() != null && !smtp.user().isBlank()) {
            s.setUsername(smtp.user());
            s.setPassword(smtp.password());
        }
        s.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties p = s.getJavaMailProperties();
        String timeout = String.valueOf(smtp.timeoutSeconds() * 1000L);
        p.put("mail.smtp.connectiontimeout", timeout);
        p.put("mail.smtp.timeout", timeout);
        p.put("mail.smtp.writetimeout", timeout);
        p.put("mail.smtp.auth", String.valueOf(smtp.user() != null && !smtp.user().isBlank()));
        // SMTP_USE_TLS = STARTTLS, as the reference (server.starttls()).
        p.put("mail.smtp.starttls.enable", String.valueOf(smtp.useTls()));
        p.put("mail.smtp.starttls.required", String.valueOf(smtp.useTls()));
        return s;
    }
}
