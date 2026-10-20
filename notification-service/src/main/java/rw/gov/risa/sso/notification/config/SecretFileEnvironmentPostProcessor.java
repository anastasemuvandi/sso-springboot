package rw.gov.risa.sso.notification.config;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
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
 * Two environment conveniences, applied before any {@code ${...}} is resolved:
 * <ol>
 *   <li>{@code <NAME>_FILE=/run/secrets/x} defines {@code NAME} as the file's trimmed content
 *       (Docker/K8s secrets). An explicit {@code NAME} wins over {@code NAME_FILE}.</li>
 *   <li>The reference's {@code NOTIFICATION_DATABASE_URL}
 *       ({@code postgresql[+driver]://user:pass@host:port/db}) is translated into
 *       {@code NOTIFICATION_DATABASE_JDBC_URL/_USER/_PASSWORD} unless those are set.</li>
 * </ol>
 * Values are never logged.
 */
public class SecretFileEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "notificationSecretFiles";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        Map<String, Object> props = new HashMap<>();
        Map<String, Object> sysEnv = env.getSystemEnvironment();
        for (Map.Entry<String, Object> e : sysEnv.entrySet()) {
            String key = e.getKey();
            if (!key.endsWith("_FILE") || key.length() <= 5) {
                continue;
            }
            String name = key.substring(0, key.length() - 5);
            if (sysEnv.containsKey(name)) {
                continue;
            }
            Path path = Path.of(String.valueOf(e.getValue()));
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                props.put(name, Files.readString(path, StandardCharsets.UTF_8).trim());
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot read secret file named by " + key, ex);
            }
        }
        Object dbUrl = props.getOrDefault("NOTIFICATION_DATABASE_URL", env.getProperty("NOTIFICATION_DATABASE_URL"));
        if (dbUrl != null && env.getProperty("NOTIFICATION_DATABASE_JDBC_URL") == null) {
            props.putAll(translateDatabaseUrl(String.valueOf(dbUrl)));
        }
        if (!props.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, props));
        }
    }

    /** {@code postgresql+asyncpg://u:p@h:5432/db} → JDBC url, user, password. Empty map if not parseable. */
    static Map<String, Object> translateDatabaseUrl(String url) {
        Map<String, Object> out = new HashMap<>();
        if (url == null || url.isBlank()) {
            return out;
        }
        String u = url.trim();
        if (u.startsWith("jdbc:")) {
            out.put("NOTIFICATION_DATABASE_JDBC_URL", u);
            return out;
        }
        int schemeEnd = u.indexOf("://");
        if (schemeEnd < 0 || !u.substring(0, schemeEnd).startsWith("postgres")) {
            return out;
        }
        URI uri = URI.create("postgresql" + u.substring(schemeEnd));
        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        jdbc.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
        if (uri.getRawQuery() != null) {
            jdbc.append('?').append(uri.getRawQuery());
        }
        out.put("NOTIFICATION_DATABASE_JDBC_URL", jdbc.toString());
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            String user = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
            out.put("NOTIFICATION_DATABASE_USER", URLDecoder.decode(user, StandardCharsets.UTF_8));
            if (colon >= 0) {
                out.put("NOTIFICATION_DATABASE_PASSWORD",
                        URLDecoder.decode(userInfo.substring(colon + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
