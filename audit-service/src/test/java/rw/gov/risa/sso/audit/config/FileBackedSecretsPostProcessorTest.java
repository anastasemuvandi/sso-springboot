package rw.gov.risa.sso.audit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class FileBackedSecretsPostProcessorTest {

    private final FileBackedSecretsPostProcessor pp = new FileBackedSecretsPostProcessor();

    @Test
    void translatesTheReferenceSqlalchemyUrl() {
        Map<String, Object> m = FileBackedSecretsPostProcessor.translateDatabaseUrl(
                "postgresql+asyncpg://user:p%40ss@postgres:5432/sso_risa?sslmode=require");
        assertThat(m).containsEntry("spring.datasource.url", "jdbc:postgresql://postgres:5432/sso_risa?sslmode=require")
                .containsEntry("spring.datasource.username", "user")
                .containsEntry("spring.datasource.password", "p@ss");
    }

    @Test
    void jdbcUrlPassesThrough() {
        assertThat(FileBackedSecretsPostProcessor.translateDatabaseUrl("jdbc:postgresql://db/x"))
                .containsExactly(Map.entry("spring.datasource.url", "jdbc:postgresql://db/x"));
    }

    @Test
    void fileWinsOverPlainValue(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("key");
        Files.writeString(f, "from-file\n");
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("t",
                Map.of("AUDIT_API_KEY", "plain", "AUDIT_API_KEY_FILE", f.toString())));
        pp.postProcessEnvironment(env, null);
        assertThat(env.getProperty("AUDIT_API_KEY")).isEqualTo("from-file");
    }

    @Test
    void emptyFileFailsStartup(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("key");
        Files.writeString(f, "  \n");
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("t", Map.of("AUDIT_API_KEY_FILE", f.toString())));
        assertThatThrownBy(() -> pp.postProcessEnvironment(env, null)).hasMessageContaining("empty");
    }

    @Test
    void databaseUrlFeedsTheDatasourceUnlessSpringUrlIsSet() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("t",
                Map.of("DATABASE_URL", "postgresql://a:b@h:1/d", "DATABASE_PASSWORD", "override")));
        pp.postProcessEnvironment(env, null);
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://h:1/d");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("override");
    }
}
