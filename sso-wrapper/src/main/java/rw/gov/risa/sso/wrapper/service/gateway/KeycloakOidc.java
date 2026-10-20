package rw.gov.risa.sso.wrapper.service.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.config.SsoProperties;

/**
 * Keycloak's OIDC / SAML protocol endpoints, reached over the INTERNAL URL ({@code KEYCLOAK_SERVER_URL}).
 * Every answer is returned as-is ({@link Answer}) so the caller decides; only "Keycloak unreachable"
 * throws (503 {@code upstream_unavailable}). Never logs a token, a password or a secret.
 */
@Component
public class KeycloakOidc {

    private static final Logger log = LoggerFactory.getLogger(KeycloakOidc.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final RestClient http;
    private final SsoProperties sso;
    private final ObjectMapper json;

    public KeycloakOidc(SsoProperties sso, GatewayProperties props, ObjectMapper json) {
        this.sso = sso;
        this.json = json;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.httpTimeout());
        rf.setReadTimeout(props.httpTimeout());
        this.http = RestClient.builder().requestFactory(rf).build();
    }

    /** An upstream answer: status and the parsed JSON body (empty when it was not JSON). */
    public record Answer(int status, Map<String, Object> body, byte[] raw, String contentType) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String error() {
            Object e = body.get("error");
            return e == null ? null : String.valueOf(e);
        }

        public String errorDescription() {
            Object d = body.get("error_description");
            return d == null ? null : String.valueOf(d);
        }

        public String str(String key) {
            Object v = body.get(key);
            return v == null ? null : String.valueOf(v);
        }
    }

    public String oidcBase() {
        return sso.keycloak().internalRealmUrl() + "/protocol/openid-connect";
    }

    // ---- grants ----------------------------------------------------------------------------

    public Answer passwordGrant(String clientId, String secret, String username, String password) {
        MultiValueMap<String, String> f = client(clientId, secret);
        f.add("grant_type", "password");
        f.add("username", username);
        f.add("password", password);
        f.add("scope", "openid");
        return postForm("/token", f, null);
    }

    public Answer authorizationCode(String clientId, String secret, String code, String redirectUri, String verifier) {
        MultiValueMap<String, String> f = client(clientId, secret);
        f.add("grant_type", "authorization_code");
        f.add("code", code);
        f.add("redirect_uri", redirectUri);
        f.add("code_verifier", verifier);
        return postForm("/token", f, null);
    }

    public Answer refresh(String clientId, String secret, String refreshToken) {
        MultiValueMap<String, String> f = client(clientId, secret);
        f.add("grant_type", "refresh_token");
        f.add("refresh_token", refreshToken);
        return postForm("/token", f, null);
    }

    public Answer clientCredentials(String clientId, String secret, String scope) {
        MultiValueMap<String, String> f = client(clientId, secret);
        f.add("grant_type", "client_credentials");
        f.add("scope", scope == null || scope.isBlank() ? "openid" : scope);
        return postForm("/token", f, null);
    }

    /** RFC 7662 introspection, the client authenticating with HTTP Basic. */
    public Answer introspect(String clientId, String secret, String token) {
        MultiValueMap<String, String> f = new LinkedMultiValueMap<>();
        f.add("token", token);
        return postForm("/token/introspect", f, basic(clientId, secret));
    }

    /**
     * Best-effort end of the session behind {@code refreshToken} (a gate refused a token Keycloak already
     * minted: it must not stay usable). Never throws.
     */
    public void revoke(String clientId, String secret, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            MultiValueMap<String, String> f = client(clientId, secret);
            f.add("refresh_token", refreshToken);
            Answer a = postForm("/logout", f, null);
            if (!a.ok()) {
                log.warn("gateway: could not end the refused session (HTTP {})", a.status());
            }
        } catch (RuntimeException e) {
            log.warn("gateway: could not end the refused session: {}", e.getMessage());
        }
    }

    // ---- documents -------------------------------------------------------------------------

    public Answer certs() {
        return get(oidcBase() + "/certs");
    }

    public Answer samlDescriptor() {
        return get(sso.keycloak().internalRealmUrl() + "/protocol/saml/descriptor");
    }

    // ---- plumbing --------------------------------------------------------------------------

    private static MultiValueMap<String, String> client(String clientId, String secret) {
        MultiValueMap<String, String> f = new LinkedMultiValueMap<>();
        f.add("client_id", clientId);
        if (secret != null && !secret.isEmpty()) {
            f.add("client_secret", secret);
        }
        return f;
    }

    private static String basic(String clientId, String secret) {
        String pair = enc(clientId) + ":" + enc(secret == null ? "" : secret);
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private Answer postForm(String path, MultiValueMap<String, String> form, String authorization) {
        try {
            RestClient.RequestBodySpec req = http.post().uri(oidcBase() + path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON);
            if (authorization != null) {
                req = req.header(HttpHeaders.AUTHORIZATION, authorization);
            }
            // Keycloak (KC_PROXY_HEADERS=xforwarded) then records the end user's IP on its own
            // LOGIN/LOGIN_ERROR events instead of the wrapper's, so brute-force and travel rules
            // see the real source.
            String clientIp = rw.gov.risa.sso.wrapper.web.RequestInfo.clientIp();
            if (clientIp != null && !clientIp.isBlank()) {
                req = req.header("X-Forwarded-For", clientIp);
            }
            return req.body(form).exchange((rq, rs) -> read(rs.getStatusCode().value(), rs.getHeaders(),
                    StreamUtils.copyToByteArray(rs.getBody())));
        } catch (RestClientException e) {
            log.error("gateway: Keycloak {} unreachable: {}", path, e.getMessage());
            throw GatewayErrors.upstream("Identity provider unreachable");
        }
    }

    private Answer get(String url) {
        try {
            return http.get().uri(url).exchange((rq, rs) -> read(rs.getStatusCode().value(), rs.getHeaders(),
                    StreamUtils.copyToByteArray(rs.getBody())));
        } catch (RestClientException e) {
            log.error("gateway: Keycloak GET unreachable: {}", e.getMessage());
            throw GatewayErrors.upstream("Identity provider unreachable");
        }
    }

    private Answer read(int status, HttpHeaders headers, byte[] raw) {
        MediaType ct = headers.getContentType();
        Map<String, Object> body = new LinkedHashMap<>();
        if (raw.length > 0 && (ct == null || ct.includes(MediaType.APPLICATION_JSON) || ct.getSubtype().endsWith("json"))) {
            try {
                body = json.readValue(raw, MAP);
            } catch (IOException e) {
                body = new LinkedHashMap<>();
            }
        }
        return new Answer(status, body, raw, ct == null ? null : ct.toString());
    }
}
