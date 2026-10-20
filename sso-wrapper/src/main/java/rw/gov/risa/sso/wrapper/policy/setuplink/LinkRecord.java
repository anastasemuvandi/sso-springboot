package rw.gov.risa.sso.wrapper.policy.setuplink;

import java.time.Instant;
import java.util.List;

/**
 * What a token's hash looks up to: the account, the SHA-256 of the token (the raw token is never
 * stored), the applications the link offers, and when it expires.
 */
public record LinkRecord(String userId, String hash, List<String> clientIds, Instant expiresAt) {

    public LinkRecord {
        clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
    }
}
