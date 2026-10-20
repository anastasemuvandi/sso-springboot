package rw.gov.risa.sso.wrapper.service.gateway;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.stereotype.Component;

/**
 * Client authentication at the protocol doors (RFC 6749 §2.3.1, spec §5.1 / §7.4). A confidential client
 * MUST present its own secret, in the form body ({@code client_secret_post}) or HTTP Basic
 * ({@code client_secret_basic}) — never both. The wrapper never looks a secret up on the caller's behalf:
 * the stored secret is read only to compare it with the presented one.
 */
@Component
public class ClientAuthenticator {

    private final GatewayDirectory directory;

    public ClientAuthenticator(GatewayDirectory directory) {
        this.directory = directory;
    }

    /** What the caller presented. {@code basic} = it came in an Authorization: Basic header. */
    public record Presented(String clientId, String secret, boolean basic) {
        @Override
        public String toString() {
            return "Presented[clientId=" + clientId + ", basic=" + basic + "]"; // never the secret
        }
    }

    /** Merge the Authorization header and the form fields; 400 {@code invalid_request} when they conflict. */
    public static Presented presented(String authorization, String formClientId, String formSecret) {
        String id = blankToNull(formClientId);
        String secret = formSecret == null || formSecret.isEmpty() ? null : formSecret;
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw GatewayErrors.invalidClient("Malformed HTTP Basic client credentials.");
            }
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                throw GatewayErrors.invalidClient("Malformed HTTP Basic client credentials.");
            }
            String basicId = urlDecode(decoded.substring(0, colon));
            String basicSecret = urlDecode(decoded.substring(colon + 1));
            if (secret != null) {
                throw GatewayErrors.invalidRequest("Use one client authentication method: HTTP Basic or client_secret in the body, not both.");
            }
            if (id != null && !id.equals(basicId)) {
                throw GatewayErrors.invalidRequest("client_id does not match the HTTP Basic credentials.");
            }
            return new Presented(basicId, basicSecret.isEmpty() ? null : basicSecret, true);
        }
        return new Presented(id, secret, false);
    }

    /**
     * Authenticate the caller as a realm client. 400 {@code invalid_request} without a client_id; 401
     * {@code invalid_client} for an unknown/disabled/bearer-only client, a confidential client that
     * presented no secret, or a wrong secret; with {@code confidentialOnly}, a public client is refused
     * too (401 {@code invalid_client}).
     */
    public RegisteredClient authenticate(Presented p, boolean confidentialOnly) {
        if (p.clientId() == null) {
            throw GatewayErrors.invalidRequest("client_id is required.");
        }
        RegisteredClient client = directory.findClient(p.clientId())
                .filter(RegisteredClient::enabled)
                .filter(c -> !c.bearerOnly())
                .orElseThrow(() -> GatewayErrors.invalidClient("Unknown or disabled client."));
        if (client.confidential()) {
            if (p.secret() == null) {
                throw GatewayErrors.invalidClient("Client authentication required: a confidential client must "
                        + "present its client_secret (form body or HTTP Basic).");
            }
            if (!directory.secretMatches(client, p.secret())) {
                throw GatewayErrors.invalidClient("Client authentication failed.");
            }
        } else if (confidentialOnly) {
            throw GatewayErrors.invalidClient("This operation requires a confidential client.");
        }
        return client;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
