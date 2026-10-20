package rw.gov.risa.sso.bff.security;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

import java.net.URI;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import rw.gov.risa.sso.bff.config.BffProperties;

/**
 * <ul>
 *   <li>{@code /api/**} and {@code GET /bff/session} need a console session; without one they answer
 *       401 JSON (never a redirect, which a {@code fetch} would follow into an HTML login page).</li>
 *   <li>Login = {@code oauth2Login} against the wrapper, PKCE S256 forced even though the client is
 *       confidential; tokens saved in the session via the session-backed authorized-client repository.</li>
 *   <li>CSRF: cookie-to-header double submit ({@code XSRF-TOKEN} → {@code X-XSRF-TOKEN}) on every
 *       state-changing request, including {@code /api/**} and {@code POST /bff/logout}.</li>
 *   <li>Security headers: CSP for a Vite SPA, frame-ancestors 'none' + X-Frame-Options DENY, nosniff,
 *       strict referrer policy, COOP, a restrictive Permissions-Policy, HSTS on HTTPS.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    static final String DEFAULT_CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; "
            + "manifest-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; "
            + "form-action 'self' %s";

    @Bean
    SecurityFilterChain bffSecurity(HttpSecurity http, BffProperties properties,
            ClientRegistrationRepository registrations, OAuth2AuthorizedClientRepository authorizedClients,
            LoginHandlers loginHandlers, RpInitiatedLogoutSuccessHandler logoutSuccess,
            @Value("${server.servlet.session.cookie.name:GORSSO_BFF_SESSION}") String sessionCookieName)
            throws Exception {

        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                registrations, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        RequestMatcher jsonPaths = new OrRequestMatcher(antMatcher("/api/**"), antMatcher("/bff/**"));

        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(c -> c.secure(properties.cookieSecure()).sameSite("Lax").path("/"));

        String csp = properties.contentSecurityPolicy() == null || properties.contentSecurityPolicy().isBlank()
                ? DEFAULT_CSP.formatted(origin(properties.publicBase())).strip()
                : properties.contentSecurityPolicy();

        http
            .authorizeHttpRequests(a -> a
                .requestMatchers(antMatcher("/api/**")).authenticated()
                .requestMatchers(antMatcher(HttpMethod.GET, "/bff/session")).authenticated()
                .anyRequest().permitAll())
            .oauth2Login(o -> o
                .loginPage("/bff/login")
                .authorizationEndpoint(e -> e.authorizationRequestResolver(resolver))
                .authorizedClientRepository(authorizedClients)
                .successHandler(loginHandlers)
                .failureHandler(loginHandlers))
            .oauth2Client(c -> c.authorizedClientRepository(authorizedClients))
            .logout(l -> l
                .logoutRequestMatcher(antMatcher(HttpMethod.POST, "/bff/logout"))
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies(sessionCookieName)
                .logoutSuccessHandler(logoutSuccess))
            .csrf(c -> c
                .csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .requestCache(r -> r.requestCache(new NullRequestCache()))
            .exceptionHandling(e -> e
                .defaultAuthenticationEntryPointFor(
                    (req, res, ex) -> JsonErrors.unauthenticated(res, "unauthenticated", "no console session"),
                    jsonPaths)
                .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/bff/login"),
                    antMatcher("/**"))
                .defaultAccessDeniedHandlerFor(
                    (req, res, ex) -> JsonErrors.write(res, HttpServletResponse.SC_FORBIDDEN, "csrf_or_access_denied",
                        "missing or invalid X-XSRF-TOKEN header, or access denied", null),
                    antMatcher("/**")))
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives(csp))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .crossOriginOpenerPolicy(c -> c.policy(
                    CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                    "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                .httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31536000)));
        return http.build();
    }

    static String origin(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getRawAuthority();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
