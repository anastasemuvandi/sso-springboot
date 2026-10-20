package rw.gov.risa.sso.wrapper.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** The committed contract is {@code sso-wrapper/openapi.json}; OpenApiContractTest fails when it is stale. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI gorssoOpenApi() {
        return new OpenAPI()
                .info(new Info().title("GoR SSO").version("1.0.0")
                        .description("Government of Rwanda Single Sign-On — wrapper API in front of Keycloak 26.2"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
