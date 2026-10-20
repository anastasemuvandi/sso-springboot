package rw.gov.risa.sso.notification.config;

import java.time.Clock;
import java.util.List;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import rw.gov.risa.sso.notification.web.ApiKeyFilter;

@Configuration
public class NotificationConfig {

    public static final String API_KEY_SCHEME = "NotificationApiKey";

    public NotificationConfig(NotificationProperties props) {
        if (props.requireSecrets()) {
            List<String> missing = SecretRequirements.missing(props);
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Missing required secrets (set them or <NAME>_FILE): "
                        + String.join(", ", missing));
            }
        }
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OpenAPI notificationOpenApi() {
        return new OpenAPI()
                .info(new Info().title("GoRSSO Notification Service").version("1.0.0")
                        .description("Security alert detection, deduplication, and multi-channel delivery"))
                .servers(List.of(new Server().url("http://localhost:8002")))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name(ApiKeyFilter.HEADER)));
    }
}
