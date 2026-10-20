package rw.gov.risa.sso.wrapper.service.ippis;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * IPPIS authentication strategies ({@code ippis_auth.py}), selected by IPPIS_AUTH_METHOD:
 * <ul>
 *   <li>{@code api_key} — header {@code X-IPPIS-API-Key} (default; the mock)</li>
 *   <li>{@code oauth2} — client-credentials bearer, cached until 30 s before expiry</li>
 *   <li>{@code mtls} — a client certificate on the TLS connection (see {@link IppisClient})</li>
 * </ul>
 * Never logs a key, secret or token.
 */
public interface IppisAuth {

    String API_KEY_HEADER = "X-IPPIS-API-Key";

    void apply(HttpHeaders headers);

    default boolean usesClientCertificate() {
        return false;
    }

    static IppisAuth from(IppisProperties props, RestClient.Builder tokenClient) {
        String method = props.auth().method() == null ? "api_key" : props.auth().method().strip().toLowerCase(Locale.ROOT);
        return switch (method) {
            case "oauth2" -> new OAuth2(props.auth(), tokenClient.build(), Clock.systemUTC());
            case "mtls" -> new Mtls();
            default -> new ApiKey(props.apiKey());
        };
    }

    record ApiKey(String key) implements IppisAuth {
        @Override
        public void apply(HttpHeaders headers) {
            headers.set(API_KEY_HEADER, key == null ? "" : key);
        }

        @Override
        public String toString() {
            return "ApiKey[****]";
        }
    }

    final class Mtls implements IppisAuth {
        @Override
        public void apply(HttpHeaders headers) {
            // The certificate is presented by the TLS layer.
        }

        @Override
        public boolean usesClientCertificate() {
            return true;
        }
    }

    final class OAuth2 implements IppisAuth {
        private final IppisProperties.Auth cfg;
        private final RestClient http;
        private final Clock clock;
        private String token;
        private Instant expiresAt = Instant.EPOCH;

        OAuth2(IppisProperties.Auth cfg, RestClient http, Clock clock) {
            this.cfg = cfg;
            this.http = http;
            this.clock = clock;
        }

        @Override
        public synchronized void apply(HttpHeaders headers) {
            if (token == null || !clock.instant().isBefore(expiresAt)) {
                fetch();
            }
            headers.setBearerAuth(token);
        }

        private void fetch() {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", cfg.oauthClientId());
            form.add("client_secret", cfg.oauthClientSecret());
            if (cfg.oauthScope() != null && !cfg.oauthScope().isBlank()) {
                form.add("scope", cfg.oauthScope());
            }
            JsonNode body = http.post().uri(cfg.oauthTokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null || !body.hasNonNull("access_token")) {
                throw new IllegalStateException("IPPIS OAuth2 token endpoint returned no access_token");
            }
            token = body.get("access_token").asText();
            long expiresIn = body.hasNonNull("expires_in") ? body.get("expires_in").asLong(300) : 300;
            expiresAt = clock.instant().plusSeconds(Math.max(0, expiresIn - 30));
        }

        @Override
        public String toString() {
            return "OAuth2[" + Map.of("token_url", String.valueOf(cfg.oauthTokenUrl())) + "]";
        }
    }
}
