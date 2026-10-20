package rw.gov.risa.sso.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.notification.service.TestProps;

class SecretConfigTest {

    @Test
    void translatesTheReferenceSqlalchemyUrl() {
        Map<String, Object> m = SecretFileEnvironmentPostProcessor.translateDatabaseUrl(
                "postgresql+asyncpg://kc26:p%40ss@postgres-kc26:5432/kc26_spike");
        assertThat(m).containsEntry("NOTIFICATION_DATABASE_JDBC_URL", "jdbc:postgresql://postgres-kc26:5432/kc26_spike")
                .containsEntry("NOTIFICATION_DATABASE_USER", "kc26")
                .containsEntry("NOTIFICATION_DATABASE_PASSWORD", "p@ss");
    }

    @Test
    void jdbcUrlPassesThroughAndJunkIsIgnored() {
        assertThat(SecretFileEnvironmentPostProcessor.translateDatabaseUrl("jdbc:postgresql://h/db"))
                .containsEntry("NOTIFICATION_DATABASE_JDBC_URL", "jdbc:postgresql://h/db");
        assertThat(SecretFileEnvironmentPostProcessor.translateDatabaseUrl("mysql://h/db")).isEmpty();
    }

    @Test
    void placeholderSecretsAreMissing() {
        NotificationProperties ok = TestProps.props("", "", "");
        assertThat(SecretRequirements.missing(ok)).isEmpty();
        NotificationProperties bad = new NotificationProperties("change-me-in-production", true, "", "", 2, 6, 22, 5,
                60, new NotificationProperties.Audit("http://a", "", 1000, 20, 5),
                new NotificationProperties.Smtp("smtp", 587, "user", "", "f", true, 5),
                ok.detector(), ok.report());
        assertThat(SecretRequirements.missing(bad))
                .containsExactly("NOTIFICATION_API_KEY", "AUDIT_API_KEY", "SMTP_PASSWORD");
    }
}
