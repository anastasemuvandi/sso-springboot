package rw.gov.risa.sso.bff.session;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import rw.gov.risa.sso.bff.config.BffProperties;
import rw.gov.risa.sso.bff.security.JsonErrors;
import rw.gov.risa.sso.bff.security.ReturnTo;

/** {@code GET /bff/session} and {@code GET /bff/login}. Logout is Spring Security's {@code POST /bff/logout}. */
@RestController
public class BffController {
    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final SessionInfoService sessionInfo;

    public BffController(OAuth2AuthorizedClientManager authorizedClientManager, SessionInfoService sessionInfo) {
        this.authorizedClientManager = authorizedClientManager;
        this.sessionInfo = sessionInfo;
    }

    /**
     * Who am I (cosmetic). Also keeps the session alive: an access token near expiry is refreshed here
     * exactly as TokenRelay would. A dead refresh token ends the session and answers 401.
     */
    @GetMapping("/bff/session")
    public ResponseEntity<SessionInfo> session(Authentication authentication, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            JsonErrors.unauthenticated(response, "unauthenticated", "no console session");
            return null;
        }
        OAuth2AuthorizedClient client;
        try {
            client = authorizedClientManager.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(token.getAuthorizedClientRegistrationId())
                    .principal(token)
                    .attribute(HttpServletRequest.class.getName(), request)
                    .attribute(HttpServletResponse.class.getName(), response)
                    .build());
        } catch (OAuth2AuthorizationException e) {
            client = null;
        }
        if (client == null) {
            endSession(request);
            JsonErrors.unauthenticated(response, "session_expired", "the console session has expired; sign in again");
            return null;
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessionInfo.describe(token, client));
    }

    /** Starts the login: remembers a safe {@code returnTo} and hands over to Spring's PKCE redirect. */
    @GetMapping("/bff/login")
    public void login(@RequestParam(name = "returnTo", required = false) String returnTo,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        ReturnTo.remember(request, ReturnTo.sanitize(returnTo));
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect(request.getContextPath() + "/oauth2/authorization/" + BffProperties.REGISTRATION_ID);
    }

    public static void endSession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // already invalidated
            }
        }
    }
}
