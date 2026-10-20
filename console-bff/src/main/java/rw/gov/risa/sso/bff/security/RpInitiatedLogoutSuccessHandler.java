package rw.gov.risa.sso.bff.security;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import rw.gov.risa.sso.bff.config.BffProperties;

/**
 * {@code POST /bff/logout}: by the time this runs the local session is invalidated (tokens gone from
 * Redis) and the session/XSRF cookies are cleared. The browser must then visit the wrapper's
 * {@code /oauth2/logout?client_id&post_logout_redirect_uri&id_token_hint} so Keycloak ends the SSO
 * session too (the reference's {@code /api/auth/logout}). A {@code fetch} cannot follow a cross-origin
 * redirect usefully, so JSON callers get {@code {"logout_url": ...}} to navigate to; a plain form
 * POST gets the 302.
 */
@Component
public class RpInitiatedLogoutSuccessHandler implements LogoutSuccessHandler {
    private final BffProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public RpInitiatedLogoutSuccessHandler(BffProperties properties) {
        this.properties = properties;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        String postLogout = properties.postLogoutRedirectUri();
        if (postLogout == null || postLogout.isBlank()) {
            postLogout = ServletUriComponentsBuilder.fromContextPath(request).path("/").build().toUriString();
        }
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(properties.publicBase() + "/oauth2/logout")
                .queryParam("client_id", properties.clientId())
                .queryParam("post_logout_redirect_uri", postLogout);
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser user
                && user.getIdToken() != null) {
            url.queryParam("id_token_hint", user.getIdToken().getTokenValue());
        }
        String logoutUrl = url.encode().build().toUriString();

        String accept = request.getHeader("Accept");
        boolean wantsJson = (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE))
                || "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
        response.setHeader("Cache-Control", "no-store");
        if (wantsJson) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), Map.of("logout_url", logoutUrl));
        } else {
            response.sendRedirect(logoutUrl);
        }
    }
}
