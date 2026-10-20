package rw.gov.risa.sso.audit.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * staging/prod refuse to start when the API key is missing or a dev default (spec §7.10); dev
 * only warns. Only names are logged, never values. An empty key list in dev means every
 * request is rejected (fail closed), not that auth is off.
 */
@Component
public class RequiredSecretsValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(RequiredSecretsValidator.class);

    static final Set<String> DEV_DEFAULTS = Set.of(
            "change-me", "change-me-in-production", "changeme", "secret", "dev-secret", "dev-only-change-me");

    private final AuditProperties props;

    public RequiredSecretsValidator(AuditProperties props) {
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();
        List<String> keys = props.acceptedKeys();
        if (keys.isEmpty()) {
            problems.add("AUDIT_API_KEY is not set");
        }
        for (String k : keys) {
            if (DEV_DEFAULTS.contains(k.toLowerCase())) {
                problems.add("AUDIT_API_KEY(S) contains a development default");
                break;
            }
        }
        if (props.workers().enabled()
                && (props.keycloak().adminPassword() == null || props.keycloak().adminPassword().isBlank())) {
            log.warn("KEYCLOAK_ADMIN_PASSWORD is not set: the certificate-expiry worker will skip its runs");
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
