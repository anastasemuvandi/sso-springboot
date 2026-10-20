package rw.gov.risa.sso.wrapper.service.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * PKCE (RFC 7636), port of {@code core/pkce.py}: only {@code S256}; {@code plain} is refused.
 * A verifier is 43–128 chars of {@code [A-Za-z0-9-._~]}; the challenge is base64url(no pad) of its SHA-256.
 */
public final class Pkce {
    private Pkce() {
    }

    public static final String S256 = "S256";

    private static final Pattern VERIFIER = Pattern.compile("^[A-Za-z0-9\\-._~]{43,128}$");
    /** An S256 challenge is exactly 43 base64url characters (32 bytes, no padding). */
    private static final Pattern S256_CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    public static boolean isValidVerifier(String verifier) {
        return verifier != null && VERIFIER.matcher(verifier).matches();
    }

    public static boolean isValidChallenge(String challenge) {
        return challenge != null && S256_CHALLENGE.matcher(challenge).matches();
    }

    public static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time check of {@code verifier} against the stored challenge; anything but S256 fails. */
    public static boolean verify(String verifier, String storedChallenge, String storedMethod) {
        if (!S256.equals(storedMethod) || storedChallenge == null || !isValidVerifier(verifier)) {
            return false;
        }
        return MessageDigest.isEqual(s256(verifier).getBytes(StandardCharsets.US_ASCII),
                storedChallenge.getBytes(StandardCharsets.US_ASCII));
    }
}
