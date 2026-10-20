package rw.gov.risa.sso.ippismock.config;

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
 * {@code <NAME>_FILE} support for the secrets in {@link #FILE_BACKED}: the file's content (trailing
 * newline stripped) becomes the property {@code <NAME>}, with precedence over the plain variable. A
 * configured but unreadable file fails startup. Values are never logged. The list is explicit because
 * some settings legitimately end in {@code _FILE} and are paths (e.g. {@code IPPIS_MOCK_DATA_FILE}).
 */
public class FileSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final java.util.List<String> FILE_BACKED = java.util.List.of("IPPIS_API_KEY", "IPPIS_WEBHOOK_SECRET");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> secrets = new HashMap<>();
        for (String name : FILE_BACKED) {
            String key = name + "_FILE";
            String path = environment.getProperty(key);
            if (path == null || path.isBlank()) {
                continue;
            }
            try {
                String value = Files.readString(Path.of(path.trim()), StandardCharsets.UTF_8);
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
