package rw.gov.risa.sso.wrapper.service.gateway;

import java.util.Map;
import java.util.Optional;

/**
 * Wrapper-issued authorization codes (password flow, spec §5.1): TTL 300 s, PKCE-bound, and CONSUMED ON
 * FIRST USE WHATEVER THE OUTCOME — {@link #consume} removes the code atomically before anything is
 * checked, so a second redemption fails even when the first one failed.
 */
public interface AuthCodeStore {

    /**
     * @param tokens   what {@code /oauth2/token} hands back (access, refresh, id token, token_type)
     * @param username the person the code was issued for (audit)
     */
    record StoredCode(Map<String, Object> tokens, String clientId, String redirectUri, String codeChallenge,
                      String codeChallengeMethod, String username) {
    }

    /** Store and return a fresh, unguessable code. */
    String issue(StoredCode code);

    /** Atomically take the code out of the store (GETDEL). Empty when unknown, expired or already used. */
    Optional<StoredCode> consume(String code);
}
