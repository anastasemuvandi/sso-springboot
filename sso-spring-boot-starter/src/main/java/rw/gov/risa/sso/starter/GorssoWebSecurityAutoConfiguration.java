package rw.gov.risa.sso.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import rw.gov.risa.sso.starter.config.GorssoProperties;
import rw.gov.risa.sso.starter.roles.GorssoJwtAuthenticationConverter;

/**
 * A default stateless resource-server chain for apps that declare no {@link SecurityFilterChain}:
 * {@code gorsso.resource-server.permit-all} is open, everything else needs a valid GoRSSO bearer.
 * Declare your own chain (or set {@code gorsso.resource-server.default-chain=false}) to take over;
 * reuse {@link GorssoJwtAuthenticationConverter} in it.
 */
@AutoConfiguration(
    after = GorssoResourceServerAutoConfiguration.class,
    before = {OAuth2ResourceServerAutoConfiguration.class, SecurityAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({HttpSecurity.class, SecurityFilterChain.class})
@ConditionalOnProperty(prefix = "gorsso", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "gorsso.resource-server", name = {"enabled", "default-chain"}, havingValue = "true", matchIfMissing = true)
public class GorssoWebSecurityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(SecurityFilterChain.class)
  @ConditionalOnBean({JwtDecoder.class, GorssoJwtAuthenticationConverter.class})
  SecurityFilterChain gorssoSecurityFilterChain(
      HttpSecurity http, GorssoProperties props, GorssoJwtAuthenticationConverter converter) throws Exception {
    // Path-pattern matchers do not depend on Spring MVC's HandlerMappingIntrospector.
    RequestMatcher[] open =
        props.resourceServer().permitAll().stream()
            .map(p -> PathPatternRequestMatcher.withDefaults().matcher(p))
            .toArray(RequestMatcher[]::new);
    http.authorizeHttpRequests(
            a -> {
              if (open.length > 0) {
                a.requestMatchers(open).permitAll();
              }
              a.anyRequest().authenticated();
            })
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Bearer-only API: no cookies are read for authentication, so CSRF does not apply.
        .csrf(c -> c.disable());
    return http.build();
  }
}
