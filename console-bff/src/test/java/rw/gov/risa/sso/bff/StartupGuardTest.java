package rw.gov.risa.sso.bff;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Staging/prod refuse to start without the client secret (SPEC §7.10). */
class StartupGuardTest {

    @Test
    void prodWithoutClientSecretFailsStartup() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(ConsoleBffApplication.class)
                .profiles("memory-session", "prod")
                .run("--server.port=0", "--bff.client-secret=", "--spring.main.banner-mode=off"))
                .hasStackTraceContaining("ADMIN_UI_CLIENT_SECRET");
    }
}
