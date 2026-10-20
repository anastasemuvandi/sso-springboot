package rw.gov.risa.sso.wrapper.service.gateway;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Verifies a token the gateway is about to hand out (or the one it was handed) with the core validator
 * ({@code security/JwtDecoderConfig}: RS256 against the realm JWKS, pinned issuer, 5 s skew, {@code azp}
 * must be a realm client). Anything that does not verify, or carries no {@code preferred_username},
 * is 401 {@code invalid_token} — it never skips the gates.
 */
@Component
public class TokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(TokenVerifier.class);

    private final JwtDecoder decoder;

    public TokenVerifier(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    /** A verified user token. */
    public record Verified(String username, String userId, String azp, String sessionId, Map<String, Object> claims) {
    }

    public Verified verifyUserToken(String token) {
        if (token == null || token.isBlank()) {
            throw GatewayErrors.invalidToken("No token to verify.");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException e) {
            log.info("gateway: token failed verification: {}", e.getMessage());
            throw GatewayErrors.invalidToken("Token verification failed.");
        } catch (RuntimeException e) {
            log.warn("gateway: token could not be verified: {}", e.toString());
            throw GatewayErrors.invalidToken("Token verification failed.");
        }
        String username = jwt.getClaimAsString("preferred_username");
        if (username == null || username.isBlank()) {
            throw GatewayErrors.invalidToken("Token has no preferred_username.");
        }
        String sid = jwt.getClaimAsString("sid");
        if (sid == null) {
            sid = jwt.getClaimAsString("session_state");
        }
        return new Verified(username, jwt.getSubject(), jwt.getClaimAsString("azp"), sid, jwt.getClaims());
    }
}
