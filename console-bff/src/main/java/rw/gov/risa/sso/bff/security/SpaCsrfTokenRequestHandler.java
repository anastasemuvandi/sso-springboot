package rw.gov.risa.sso.bff.security;

import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

/**
 * Cookie-to-header double submit for a SPA: the SPA copies the {@code XSRF-TOKEN} cookie into the
 * {@code X-XSRF-TOKEN} header (or the {@code _csrf} field of a form POST). Loading the deferred token on every request makes the cookie appear on the very first page load.
 */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        this.xor.handle(request, response, csrfToken);
        csrfToken.get();
    }

    /**
     * The SPA sends the RAW cookie value, either as the {@code X-XSRF-TOKEN} header (fetch) or as the
     * {@code _csrf} form field (the top-level sign-out form POST), so both resolve without XOR.
     */
    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        return this.plain.resolveCsrfTokenValue(request, csrfToken);
    }
}
