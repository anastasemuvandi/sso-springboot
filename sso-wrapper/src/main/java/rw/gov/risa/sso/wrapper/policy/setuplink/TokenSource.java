package rw.gov.risa.sso.wrapper.policy.setuplink;

import java.security.SecureRandom;

/** Where token bytes come from. Injectable for tests; production uses {@link #secure()}. */
@FunctionalInterface
public interface TokenSource {

    byte[] nextBytes(int count);

    static TokenSource secure() {
        SecureRandom random = new SecureRandom();
        return count -> {
            byte[] b = new byte[count];
            random.nextBytes(b);
            return b;
        };
    }
}
