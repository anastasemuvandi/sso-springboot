package rw.gov.risa.sso.audit.config;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
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
 * Two environment conveniences, applied before anything binds:
 * <ol>
 *   <li>{@code <NAME>_FILE=/run/secrets/...} is expanded into {@code <NAME>} for the names in
 *       {@link #FILE_BACKED}; the file wins over a plain value; an unreadable or empty file fails
 *       startup rather than yielding a blank credential.</li>
 *   <li>The reference's SQLAlchemy-style {@code DATABASE_URL}
 *       ({@code postgresql+asyncpg://user:pass@host:5432/db}) is translated into
 *       {@code spring.datasource.url/username/password}, so the same .env feeds both stacks.
 *       {@code SPRING_DATASOURCE_URL} (or a {@code jdbc:} DATABASE_URL) takes precedence;
 *       {@code DATABASE_USER}/{@code DATABASE_PASSWORD} override the URL's credentials.</li>
 * </ol>
 */
public class FileBackedSecretsPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final List<String> FILE_BACKED = List.of(
            "AUDIT_API_KEY", "AUDIT_API_KEYS", "KEYCLOAK_ADMIN_PASSWORD", "DATABASE_PASSWORD",
            "DATABASE_URL", "REDIS_URL", "SPRING_DATASOURCE_PASSWORD");

    static final String SOURCE_NAME = "fileBackedSecrets";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (String name : FILE_BACKED) {
            String path = environment.getProperty(name + "_FILE");
            if (path != null && !path.isBlank()) {
                resolved.put(name, read(name, path.trim()));
            }
        }
        if (!resolved.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, resolved));
        }

        Map<String, Object> ds = new LinkedHashMap<>();
        String springUrl = environment.getProperty("SPRING_DATASOURCE_URL");
        String dbUrl = environment.getProperty("DATABASE_URL");
        if ((springUrl == null || springUrl.isBlank()) && dbUrl != null && !dbUrl.isBlank()) {
            ds.putAll(translateDatabaseUrl(dbUrl.trim()));
        }
        String user = environment.getProperty("DATABASE_USER");
        if (user != null && !user.isBlank()) {
            ds.put("spring.datasource.username", user);
        }
        String pw = environment.getProperty("DATABASE_PASSWORD");
        if (pw != null && !pw.isBlank()) {
            ds.put("spring.datasource.password", pw);
        }
        if (!ds.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("auditDatabaseUrl", ds));
        }
    }

    /** postgresql[+driver]://user:pass@host:port/db?x=y → jdbc:postgresql://host:port/db?x=y + creds. */
    static Map<String, Object> translateDatabaseUrl(String url) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (url.startsWith("jdbc:")) {
            out.put("spring.datasource.url", url);
            return out;
        }
        int scheme = url.indexOf("://");
        if (scheme < 0 || !url.substring(0, scheme).startsWith("postgres")) {
            throw new IllegalStateException("DATABASE_URL must be a postgresql:// or jdbc:postgresql: URL");
        }
        URI uri = URI.create("postgresql" + url.substring(scheme));
        String hostPort = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        out.put("spring.datasource.url", "jdbc:postgresql://" + hostPort + uri.getRawPath() + query);
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            String u = colon < 0 ? userInfo : userInfo.substring(0, colon);
            out.put("spring.datasource.username", URLDecoder.decode(u, StandardCharsets.UTF_8));
            if (colon >= 0) {
                out.put("spring.datasource.password",
                        URLDecoder.decode(userInfo.substring(colon + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
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
