package rw.gov.risa.sso.audit.config;

import java.util.List;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

import rw.gov.risa.sso.audit.web.ApiKeyFilter;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static final String SCHEME = "AuditApiKey";

    @Bean
    OpenAPI auditOpenApi() {
        return new OpenAPI()
                .info(new Info().title("GoR SSO — Audit Service").version("1.0.0")
                        .description("Tamper-evident audit log with hash chain, CSV/PDF export, and service health "
                                + "monitoring. Every /audit/** route requires the X-Audit-API-Key header."))
                // Fixed so the committed openapi.json does not depend on the port it was generated on.
                .servers(List.of(new Server().url("http://audit-service:8001").description("compose network")))
                .components(new Components().addSecuritySchemes(SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name(ApiKeyFilter.HEADER)));
    }

    /** Marks every /audit/** operation as requiring the API key. */
    @Bean
    OpenApiCustomizer auditSecurity() {
        return api -> api.getPaths().forEach((path, item) -> {
            if (path.startsWith("/audit")) {
                item.readOperations().forEach(op -> op.addSecurityItem(new SecurityRequirement().addList(SCHEME)));
            }
        });
    }
}
