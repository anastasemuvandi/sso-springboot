package rw.gov.risa.sso.bff.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * {@code <NAME>_FILE} support: for every environment variable ending in {@code _FILE}, the file's
 * content (trailing newline stripped) becomes the property {@code <NAME>}, with precedence over the
 * plain variable. A configured but unreadable file fails startup. Values are never logged.
 */
public class FileSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> secrets = new HashMap<>();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            String key = e.getKey();
            if (!key.endsWith("_FILE") || key.length() <= 5 || e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            String name = key.substring(0, key.length() - 5);
            try {
                String value = Files.readString(Path.of(e.getValue().trim()), StandardCharsets.UTF_8);
                secrets.put(name, value.stripTrailing());
            } catch (IOException ex) {
                throw new IllegalStateException("cannot read secret file named by " + key, ex);
            }
        }
        if (!secrets.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("fileSecrets", secrets));
        }
    }
}
