package rw.gov.risa.sso.mfa.policy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Access to the declarative {@code browser-totp} flow definition
 * ({@value MfaConstants#FLOW_DEFINITION_RESOURCE}). This module has no JSON library on
 * purpose (it is loaded inside Keycloak); callers parse the text with their own
 * (Jackson in the wrapper). Shape documented in {@code docs/implemented/mfa-keycloak.md}.
 */
public final class MfaFlowDefinition {

    private MfaFlowDefinition() {
    }

    /** Opens the resource; the caller closes it. */
    public static InputStream open() {
        InputStream in = MfaFlowDefinition.class.getResourceAsStream(MfaConstants.FLOW_DEFINITION_RESOURCE);
        if (in == null) {
            throw new IllegalStateException(MfaConstants.FLOW_DEFINITION_RESOURCE + " missing from the classpath");
        }
        return in;
    }

    /** The whole definition as UTF-8 JSON text. */
    public static String json() {
        try (InputStream in = open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
