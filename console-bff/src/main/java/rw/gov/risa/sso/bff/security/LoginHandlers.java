package rw.gov.risa.sso.bff.security;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.bff.session.SessionInfo;
import rw.gov.risa.sso.bff.session.SessionInfoService;

/**
 * After the callback: go to the remembered {@code returnTo}, else to the caller's landing page
 * (platform admins {@code /dashboard}, institution admins {@code /institution}). A failed login
 * (state mismatch, the wrapper refusing the code exchange with {@code access_denied} /
 * {@code mfa_required} / {@code join_key_*}, ...) lands on {@code /?error=<code>}, like the reference.
 */
@Component
public class LoginHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    private static final Logger log = LoggerFactory.getLogger(LoginHandlers.class);
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final OAuth2AuthorizedClientRepository authorizedClients;
    private final SessionInfoService sessionInfo;

    public LoginHandlers(OAuth2AuthorizedClientRepository authorizedClients, SessionInfoService sessionInfo) {
        this.authorizedClients = authorizedClients;
        this.sessionInfo = sessionInfo;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        String target = ReturnTo.pop(request);
        if (target == null && authentication instanceof OAuth2AuthenticationToken token) {
            OAuth2AuthorizedClient client = authorizedClients.loadAuthorizedClient(
                    token.getAuthorizedClientRegistrationId(), token, request);
            SessionInfo info = sessionInfo.describe(token, client);
            target = info.landing();
        }
        response.sendRedirect(request.getContextPath() + (target == null ? "/" : target));
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        String code = "login_failed";
        if (exception instanceof OAuth2AuthenticationException oauth) {
            String c = oauth.getError().getErrorCode();
            if (c != null && SAFE_CODE.matcher(c).matches()) {
                code = c;
            }
        }
        log.info("bff: login failed ({})", code);
        response.sendRedirect(request.getContextPath() + "/?error=" + code);
    }
}
