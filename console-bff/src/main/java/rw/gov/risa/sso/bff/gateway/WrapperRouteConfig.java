package rw.gov.risa.sso.bff.gateway;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.removeRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.stripPrefix;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.filter.TokenRelayFilterFunctions.tokenRelay;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import rw.gov.risa.sso.bff.config.BffProperties;
import rw.gov.risa.sso.bff.config.CorrelationIdFilter;
import rw.gov.risa.sso.bff.security.JsonErrors;
import rw.gov.risa.sso.bff.session.BffController;

/**
 * Spring Cloud Gateway Server MVC route: {@code /api/**} → wrapper, {@code /api} stripped, with
 * {@code TokenRelay}: the CALLER's own access token (refreshed through the authorized-client manager
 * when near expiry) as {@code Authorization: Bearer}. The browser's cookies, its XSRF header and any
 * Authorization header it sent are NOT forwarded.
 *
 * <p>Filter order (outermost first): {@link #sessionGuard} → TokenRelay → request rewriting.
 */
@Configuration
public class WrapperRouteConfig {
    private static final Logger log = LoggerFactory.getLogger(WrapperRouteConfig.class);
    /** A dead refresh token / revoked session: the session is over. */
    private static final Set<String> SESSION_OVER = Set.of("invalid_grant", "invalid_token", "invalid_client",
            "unauthorized_client", "access_denied", "mfa_required");

    @Bean
    RouterFunction<ServerResponse> wrapperApiRoute(BffProperties properties,
            OAuth2AuthorizedClientRepository authorizedClients) {
        return route("wrapper-api")
                .route(path("/api/**"), http())
                .filter(sessionGuard(authorizedClients))
                .filter(tokenRelay())
                .before(uri(properties.internalBase()))
                .before(stripPrefix(1))
                .before(removeRequestHeader("Cookie"))
                .before(removeRequestHeader("X-XSRF-TOKEN"))
                .before(WrapperRouteConfig::correlationId)
                .build();
    }

    static ServerRequest correlationId(ServerRequest request) {
        String id = MDC.get(CorrelationIdFilter.MDC_KEY);
        return id == null ? request
                : ServerRequest.from(request).headers(h -> h.set(CorrelationIdFilter.HEADER, id)).build();
    }

    /**
     * Turns the token states the SPA must react to into JSON instead of a 500: no authorized client in
     * the session, or a refresh the wrapper refuses → 401 (session ended, sign in again); the wrapper
     * unreachable for the refresh or the call → 502 (session kept).
     */
    static HandlerFilterFunction<ServerResponse, ServerResponse> sessionGuard(
            OAuth2AuthorizedClientRepository authorizedClients) {
        return (request, next) -> {
            HttpServletRequest servlet = request.servletRequest();
            Authentication principal = (Authentication) servlet.getUserPrincipal();
            OAuth2AuthorizedClient client = principal instanceof OAuth2AuthenticationToken token
                    ? authorizedClients.loadAuthorizedClient(token.getAuthorizedClientRegistrationId(), token, servlet)
                    : null;
            if (client == null) {
                BffController.endSession(servlet);
                return error(HttpStatus.UNAUTHORIZED, "unauthenticated", "no console session");
            }
            Instant expiresAt = client.getAccessToken().getExpiresAt();
            if (client.getRefreshToken() == null && expiresAt != null && expiresAt.isBefore(Instant.now())) {
                // Expired and nothing to refresh it with: relaying it would only earn a 401 upstream.
                BffController.endSession(servlet);
                return error(HttpStatus.UNAUTHORIZED, "session_expired", "the console session has expired; sign in again");
            }
            try {
                return next.handle(request);
            } catch (OAuth2AuthorizationException e) {
                String code = e.getError().getErrorCode();
                if (SESSION_OVER.contains(code)) {
                    log.info("bff: token refresh refused by the wrapper ({}); ending the session", code);
                    BffController.endSession(servlet);
                    return error(HttpStatus.UNAUTHORIZED, "session_expired",
                            "the console session has expired; sign in again");
                }
                log.warn("bff: token refresh failed ({})", code);
                return error(HttpStatus.BAD_GATEWAY, "token_refresh_failed", "could not refresh the session token");
            } catch (ResourceAccessException e) {
                log.warn("bff: wrapper unreachable ({})", e.getMessage());
                return error(HttpStatus.BAD_GATEWAY, "wrapper_unavailable", "the SSO wrapper is unreachable");
            }
        };
    }

    private static ServerResponse error(HttpStatus status, String code, String message) {
        Map<String, Object> details = status == HttpStatus.UNAUTHORIZED
                ? Map.of("authenticated", false, "login_url", "/bff/login") : Map.of();
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .body(JsonErrors.envelope(code, message, details));
    }
}
