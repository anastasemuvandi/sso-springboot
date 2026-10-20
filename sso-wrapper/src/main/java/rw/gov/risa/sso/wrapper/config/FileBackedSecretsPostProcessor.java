package rw.gov.risa.sso.wrapper.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Expands {@code <NAME>_FILE=/run/secrets/...} into {@code <NAME>} before anything binds, so a
 * secret can come from a mounted file (Docker/Kubernetes secrets, Vault agent, systemd
 * credentials) instead of the environment. The {@code _FILE} form wins over a plain value.
 *
 * <p>Only the names in {@link #FILE_BACKED} participate: a blanket "any *_FILE" rule would
 * misfire on settings that are genuinely paths. A {@code _FILE} that is unreadable or empty
 * fails startup rather than silently yielding a blank credential.
 */
public class FileBackedSecretsPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final List<String> FILE_BACKED = List.of(
            "KEYCLOAK_CLIENT_SECRET",
            "KEYCLOAK_ADMIN_PASSWORD",
            "SECRET_KEY",
            "AUDIT_API_KEY",
            "SMTP_PASSWORD",
            "REDIS_PASSWORD",
            "IDENTITY_DATABASE_PASSWORD",
            "IPPIS_API_KEY",
            "IPPIS_OAUTH_CLIENT_SECRET",
            "IPPIS_MTLS_KEY",
            "IPPIS_WEBHOOK_SECRET",
            "ACCESS_MATRIX_SIGNING_KEY");

    static final String SOURCE_NAME = "fileBackedSecrets";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (String name : FILE_BACKED) {
            String path = environment.getProperty(name + "_FILE");
            if (path == null || path.isBlank()) {
                continue;
            }
            resolved.put(name, read(name, path.trim()));
        }
        if (!resolved.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, resolved));
        }
    }

    static String read(String name, String path) {
        String value;
        try {
            value = Files.readString(Path.of(path), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException(name + "_FILE points at '" + path + "', which cannot be read: "
                    + e.getClass().getSimpleName() + ". Check the secret mount.", e);
        }
        if (value.isEmpty()) {
            throw new IllegalStateException(name + "_FILE points at '" + path
                    + "', which is empty. Refusing to start with a blank credential.");
        }
        return value;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
