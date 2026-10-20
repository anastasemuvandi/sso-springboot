package rw.gov.risa.sso.wrapper.service.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The wrapper's own mailer. The relay itself is {@code spring.mail.*} (SMTP_HOST, SMTP_PORT, SMTP_USER,
 * SMTP_PASSWORD, SMTP_STARTTLS, SMTP_USE_SSL); an empty SMTP_HOST means "not configured".
 *
 * @param from                SMTP_FROM
 * @param loginUrl            SSO_LOGIN_URL: where a person is told to sign in (the portal)
 * @param notifyAccessChanges NOTIFY_ACCESS_CHANGES: tell existing accounts when apps are granted/revoked
 */
@ConfigurationProperties(prefix = "sso.mail")
public record MailSettings(
        @DefaultValue("sso-noreply@gov.rw") String from,
        @DefaultValue("http://localhost:3001") String loginUrl,
        @DefaultValue("true") boolean notifyAccessChanges) {
}
