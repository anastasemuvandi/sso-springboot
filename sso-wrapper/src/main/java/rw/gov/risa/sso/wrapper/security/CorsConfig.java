package rw.gov.risa.sso.wrapper.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import rw.gov.risa.sso.wrapper.config.SsoProperties;

/** Strict allow-list. Credentials are allowed, so a wildcard is refused at startup. */
@Configuration
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(SsoProperties props) {
        List<String> origins = props.cors().allowedOrigins().stream().map(String::trim).filter(s -> !s.isEmpty())
                .toList();
        if (origins.stream().anyMatch(o -> o.contains("*"))) {
            throw new IllegalStateException("ALLOWED_ORIGINS must not include '*'. Set explicit origins "
                    + "(credentials cannot be sent with wildcard origins).");
        }
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(origins);
        c.setAllowCredentials(true);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Accept-Language",
                "X-Requested-With", "X-Correlation-Id"));
        c.setExposedHeaders(List.of("X-Correlation-Id", "Content-Disposition"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", c);
        return source;
    }
}
