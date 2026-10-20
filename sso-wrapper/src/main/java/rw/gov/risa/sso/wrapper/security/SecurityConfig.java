package rw.gov.risa.sso.wrapper.security;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;

import rw.gov.risa.sso.wrapper.config.SsoProperties;

/**
 * The wrapper is an OAuth2 resource server. Gates:
 * <ul>
 *   <li>{@link RouteRules#ADMIN_TIER}: realm role super_admin | admin | system_admin
 *       (super_admin passes every platform route)</li>
 *   <li>{@link RouteRules#SELF_SCOPED}: authenticated; the feature resolves the caller's
 *       institution itself and holds the boundary</li>
 *   <li>{@link RouteRules#PUBLIC}: protocol surface and signed/throttled public pages</li>
 * </ul>
 * Finer rules use {@code @PreAuthorize} (method security is on). Not loaded in ops-command mode
 * (no web server), where there is no request to secure.
 */
@Configuration
@EnableMethodSecurity
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
public class SecurityConfig {

    static final String CSP_API = "default-src 'none'; frame-ancestors 'none'";

    @Bean
    SecurityFilterChain api(HttpSecurity http, CallerTokenConverter converter, JsonSecurityErrorHandlers errors,
            @org.springframework.beans.factory.annotation.Qualifier("corsConfigurationSource") CorsConfigurationSource cors,
            SsoProperties props) throws Exception {
        String[] adminTierRoles = CallerPrincipal.ADMIN_TIER_ROLES.toArray(String[]::new);
        http
                .csrf(c -> c.disable())
                .cors(c -> c.configurationSource(cors))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .headers(h -> {
                    h.frameOptions(f -> f.deny());
                    h.contentTypeOptions(c -> { });
                    h.referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                    h.contentSecurityPolicy(c -> c.policyDirectives(CSP_API));
                    if (props.debug()) {
                        h.httpStrictTransportSecurity(s -> s.disable());
                    } else {
                        h.httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31536000));
                    }
                })
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(matchers(RouteRules.PUBLIC)).permitAll()
                        .requestMatchers(matchers(RouteRules.AUTHENTICATED)).authenticated()
                        .requestMatchers(matchers(RouteRules.SELF_SCOPED)).authenticated()
                        .requestMatchers(matchers(RouteRules.ADMIN_TIER))
                        .access(AuthorityAuthorizationManager.hasAnyRole(adminTierRoles))
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(bearerTokenResolver())
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors)
                        .jwt(j -> j.jwtAuthenticationConverter(converter)))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        return http.build();
    }

    static RequestMatcher[] matchers(List<String> patterns) {
        return patterns.stream().map(AntPathRequestMatcher::new).toArray(RequestMatcher[]::new);
    }

    static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        RequestMatcher ignored = new OrRequestMatcher(matchers(RouteRules.IGNORE_BEARER));
        return (HttpServletRequest request) -> ignored.matches(request) ? null : delegate.resolve(request);
    }
}
