package rw.gov.risa.sso.wrapper.service.provisioning;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * A password nobody will ever type, for an account nobody may use yet (reference
 * {@code _temporary_password}). 32 random bytes, URL-safe, plus {@code Aa1!} so the realm's
 * {@code length(10) and upperCase(1) and lowerCase(1) and digits(1)} policy can never refuse it.
 *
 * <p>It is set as a TEMPORARY credential and forgotten: never returned, logged or emailed. It exists only
 * so the account is not credential-less (which would let whoever asks first claim it by self-service reset).
 */
public final class TemporaryPasswords {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswords() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "Aa1!";
    }
}
