package rw.gov.risa.sso.wrapper.service.gateway;

import java.util.List;

import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.policy.setuplink.SetupLinkPolicy;

/**
 * The "back to sign in" link on the public account pages. Only a RELATIVE path is accepted — what the
 * Keycloak theme sends ({@code loginRestartFlowUrl}, e.g. {@code /realms/<realm>/login-actions/restart?...})
 * — and it is resolved against the Keycloak public URL. An absolute or protocol-relative ({@code //host})
 * value, or one with a backslash or control character, is dropped: the page is never an open redirect.
 */
@Component
public class AccountPageLinks {

    private static final int MAX_LENGTH = 2048;

    private final SsoProperties sso;

    public AccountPageLinks(SsoProperties sso) {
        this.sso = sso;
    }

    /** The relative value to carry forward (forms, language switch), or null when not acceptable. */
    public String acceptedReturnTo(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > MAX_LENGTH) {
            return null;
        }
        if (!candidate.startsWith("/") || candidate.startsWith("//")) {
            return null;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char ch = candidate.charAt(i);
            if (ch == '\\' || ch < 0x20 || ch == 0x7f) {
                return null;
            }
        }
        return backUrl(candidate) == null ? null : candidate;
    }

    /** The absolute URL for an accepted relative {@code return_to}, on the Keycloak public origin; else null. */
    public String backUrl(String candidate) {
        if (candidate == null || !candidate.startsWith("/") || candidate.startsWith("//")) {
            return null;
        }
        return SetupLinkPolicy.safeReturnUrl(candidate, List.of(sso.keycloak().publicUrl()));
    }
}
