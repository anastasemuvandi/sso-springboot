package rw.gov.risa.sso.wrapper.service.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;

/**
 * THE gates (spec §5.1, invariant §7.3), in one place so no door can forget one. Every door a user token
 * leaves by calls {@link #pass}; nothing else hands a user token out.
 *
 * <ol>
 *   <li>the token is verified with the core JWT validator, must carry {@code preferred_username} and must
 *       have been issued to the client asking (401 / 400 otherwise);</li>
 *   <li>per-client access — 403 {@code access_denied}, audited {@code LOGIN_DENIED} (fails CLOSED: 503 when
 *       Keycloak cannot answer);</li>
 *   <li>join key — 403 {@code join_key_missing|join_key_invalid|join_key_not_unique} (fails OPEN on an
 *       infrastructure error, as the spec asks);</li>
 *   <li>password-grant MFA — 403 {@code mfa_required} when TOTP applies to the client, the person is not
 *       {@code mfa-exempt} and the token's {@code acr} is below 2. Applied on every door a password-derived
 *       token can leave by, and on refresh; NOT on a Keycloak browser code, whose login already ran
 *       Keycloak's own TOTP step-up.</li>
 * </ol>
 */
@Service
public class TokenGates {

    private static final Logger log = LoggerFactory.getLogger(TokenGates.class);

    /** Each door a user token leaves by. */
    public enum Door {
        LOGIN("/auth/login", "password", true),
        AUTHORIZE_INTERNAL("/oauth2/authorize-internal", "authorize_internal", true),
        /** {@code /oauth2/token} redeeming a wrapper-issued (password-flow) code. */
        TOKEN_WRAPPER_CODE("/oauth2/token", "authorization_code", true),
        /** {@code /oauth2/token} redeeming a Keycloak (browser-flow) code. */
        TOKEN_BROWSER_CODE("/oauth2/token", "authorization_code", false),
        TOKEN_REFRESH("/oauth2/token", "refresh_token", true);

        private final String path;
        private final String grantType;
        private final boolean passwordMfa;

        Door(String path, String grantType, boolean passwordMfa) {
            this.path = path;
            this.grantType = grantType;
            this.passwordMfa = passwordMfa;
        }

        public String path() {
            return path;
        }

        public String grantType() {
            return grantType;
        }

        /** Whether the password-grant MFA gate applies at this door. */
        public boolean passwordMfa() {
            return passwordMfa;
        }
    }

    private final TokenVerifier verifier;
    private final ClientAccessService access;
    private final JoinKeyService joinKeys;
    private final MfaService mfa;

    public TokenGates(TokenVerifier verifier, ClientAccessService access, JoinKeyService joinKeys, MfaService mfa) {
        this.verifier = verifier;
        this.access = access;
        this.joinKeys = joinKeys;
        this.mfa = mfa;
    }

    /**
     * Run every gate for {@code accessToken} leaving by {@code door} to {@code clientId}. Returns the verified
     * token; throws the refusal ({@code ApiException}) otherwise. Refusals are audited by the gate that refused.
     */
    public TokenVerifier.Verified pass(Door door, String accessToken, String clientId, String requestIp) {
        TokenVerifier.Verified token = verifier.verifyUserToken(accessToken);
        if (clientId == null || !clientId.equals(token.azp())) {
            log.warn("gateway: {} token for client {} presented for {}", door, token.azp(), clientId);
            throw GatewayErrors.invalidGrant("The token was not issued to this client.");
        }
        access.enforceLogin(token.username(), clientId, requestIp, door.grantType());
        joinKeys.enforceLogin(token.username(), clientId, requestIp, door.grantType());
        if (door.passwordMfa()) {
            mfa.enforcePasswordGrant(token.claims(), token.username(), clientId, requestIp, door.path());
        }
        return token;
    }
}
