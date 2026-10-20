package rw.gov.risa.sso.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Backend-for-frontend of the React console.
 *
 * <p>The SPA never sees a token. This service runs the OIDC authorization-code + PKCE (S256) login
 * against the WRAPPER's gateway ({@code /oauth2/*}) as the confidential client {@code sso-admin-ui},
 * keeps the token set server-side in the (Redis-backed) session behind an HttpOnly cookie, refreshes
 * it, and relays the CALLER's own access token to the wrapper for every {@code /api/**} call. It has
 * no service account and decides nothing: every rule is enforced by the wrapper.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ConsoleBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConsoleBffApplication.class, args);
    }
}
