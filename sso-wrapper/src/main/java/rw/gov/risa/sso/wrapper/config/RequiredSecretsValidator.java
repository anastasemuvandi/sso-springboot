package rw.gov.risa.sso.wrapper.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * staging/prod refuse to start when a required secret is missing or still a dev default
 * (spec §7.10). dev only warns. Names only are ever logged, never values.
 */
@Component
public class RequiredSecretsValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(RequiredSecretsValidator.class);

    /** Secrets every deployed wrapper needs. Optional integrations are checked by their own feature. */
    public static final List<String> REQUIRED = List.of(
            "KEYCLOAK_CLIENT_SECRET", "SECRET_KEY", "AUDIT_API_KEY", "ACCESS_MATRIX_SIGNING_KEY");

    private static final Set<String> DEV_DEFAULTS = Set.of(
            "change-me", "change-me-in-production", "changeme", "secret", "dev-secret", "dev-only-change-me");

    private final Environment env;
    private final SsoProperties props;

    public RequiredSecretsValidator(Environment env, SsoProperties props) {
        this.env = env;
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();
        for (String name : REQUIRED) {
            String value = env.getProperty(name);
            if (value == null || value.isBlank()) {
                problems.add(name + " is not set");
            } else if (DEV_DEFAULTS.contains(value.trim().toLowerCase())) {
                problems.add(name + " is a development default");
            }
        }
        if (problems.isEmpty()) {
            return;
        }
        if (props.isProductionLike()) {
            throw new IllegalStateException("Refusing to start in '" + props.environment()
                    + "' with missing secrets: " + String.join("; ", problems)
                    + ". Supply them via the environment or <NAME>_FILE.");
        }
        log.warn("Secrets missing or defaulted (allowed only in dev): {}", problems);
    }
}
