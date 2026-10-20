package rw.gov.risa.sso.audit.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RequiredSecretsValidatorTest {

    private static AuditProperties props(String env, String key) {
        return new AuditProperties(env, key, null, new AuditProperties.Workers(false),
                new AuditProperties.Health(List.of(), Map.of(), null, Duration.ofSeconds(1)),
                new AuditProperties.Keycloak("http://kc", "r", "admin", null, "master", "admin-cli"),
                new AuditProperties.Retention(90));
    }

    @Test
    void prodRefusesMissingOrDefaultKey() {
        assertThatThrownBy(() -> new RequiredSecretsValidator(props("prod", null)).afterPropertiesSet())
                .hasMessageContaining("AUDIT_API_KEY");
        assertThatThrownBy(() -> new RequiredSecretsValidator(props("staging", "change-me-in-production"))
                .afterPropertiesSet()).hasMessageContaining("development default");
    }

    @Test
    void devOnlyWarnsAndProdAcceptsARealKey() {
        assertThatCode(() -> new RequiredSecretsValidator(props("dev", null)).afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThatCode(() -> new RequiredSecretsValidator(props("prod", "k-7f3a9c")).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
