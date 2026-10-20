package rw.gov.risa.sso.wrapper.api.oauth2;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.service.gateway.ClientAuthenticator;
import rw.gov.risa.sso.wrapper.service.gateway.OAuth2GatewayService;
import rw.gov.risa.sso.wrapper.service.gateway.OAuth2GatewayService.AuthorizeRequest;
import rw.gov.risa.sso.wrapper.service.gateway.OAuth2GatewayService.InternalAuthorizeRequest;
import rw.gov.risa.sso.wrapper.service.gateway.OAuth2GatewayService.TokenRequest;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * {@code /oauth2/*}: the OIDC gateway (spec §5.1). Parameters are bound as optional and validated in the
 * service so a missing one is an RFC 6749 {@code invalid_request} (400), not a generic 422.
 *
 * <p>Errors here carry the RFC 6749 §5.2 body AND the wrapper envelope:
 * {@code {"error", "error_description", "message", "details"}} with {@code Cache-Control: no-store}; a 401
 * {@code invalid_client} also carries {@code WWW-Authenticate: Basic}.
 */
@RestController
@RequestMapping("/oauth2")
@Tag(name = "OAuth2 / OIDC")
public class OAuth2Controller {

    private final OAuth2GatewayService oauth;

    public OAuth2Controller(OAuth2GatewayService oauth) {
        this.oauth = oauth;
    }

    @GetMapping("/authorize")
    @RateLimit(value = "60/minute", key = RateLimit.Key.IP)
    @Operation(summary = "Browser authorization request: validates the client, the redirect URI (exact) and PKCE "
            + "(S256 only), then 302 to Keycloak")
    public ResponseEntity<Void> authorize(
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "response_type", required = false) String responseType,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "nonce", required = false) String nonce,
            @RequestParam(value = "acr_values", required = false) String acrValues,
            @RequestParam(value = "prompt", required = false) String prompt,
            @RequestParam(value = "login_hint", required = false) String loginHint) {
        URI target = oauth.authorize(new AuthorizeRequest(clientId, redirectUri, responseType, codeChallenge,
                codeChallengeMethod, scope, state, nonce, acrValues, prompt, loginHint), RequestInfo.clientIp());
        return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
    }

    /** {@code AuthorizeInternalRequest}. */
    public record AuthorizeInternalBody(
            @JsonProperty("client_id") String clientId,
            @JsonProperty("redirect_uri") String redirectUri,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("id_token") String idToken,
            @JsonProperty("code_challenge") String codeChallenge,
            @JsonProperty("code_challenge_method") String codeChallengeMethod) {
        @Override
        public String toString() {
            return "AuthorizeInternalBody[clientId=" + clientId + ", redirectUri=" + redirectUri + "]";
        }
    }

    @PostMapping(value = "/authorize-internal", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimit(value = "60/minute", key = RateLimit.Key.IP)
    @Operation(summary = "Password-flow bridge: a /auth/login token (bearer) becomes a wrapper authorization code "
            + "(Redis, 300 s, PKCE-bound, single use) after every gate")
    public Map<String, String> authorizeInternal(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody AuthorizeInternalBody body) {
        String code = oauth.authorizeInternal(bearer(authorization), new InternalAuthorizeRequest(body.clientId(),
                body.redirectUri(), body.refreshToken(), body.idToken(), body.codeChallenge(),
                body.codeChallengeMethod()), RequestInfo.clientIp());
        return Map.of("authorization_code", code);
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @RateLimit(value = "120/minute", key = RateLimit.Key.IP)
    @Operation(summary = "Token endpoint: authorization_code (Keycloak or wrapper code, PKCE), refresh_token, "
            + "client_credentials. Confidential clients authenticate (form or HTTP Basic)")
    public ResponseEntity<Map<String, Object>> token(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(value = "grant_type", required = false) String grantType,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "client_secret", required = false) String clientSecret,
            @RequestParam(value = "refresh_token", required = false) String refreshToken,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "code_verifier", required = false) String codeVerifier) {
        ClientAuthenticator.Presented presented = ClientAuthenticator.presented(authorization, clientId, clientSecret);
        Map<String, Object> tokens = oauth.token(
                new TokenRequest(grantType, code, redirectUri, refreshToken, scope, codeVerifier), presented,
                RequestInfo.clientIp());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(tokens);
    }

    @GetMapping("/.well-known/openid-configuration")
    @Operation(summary = "OpenID Provider metadata (wrapper endpoints; issuer = the Keycloak issuer)")
    public Map<String, Object> discovery() {
        return oauth.discovery();
    }

    @GetMapping("/logout")
    @RateLimit(value = "60/minute", key = RateLimit.Key.IP)
    @Operation(summary = "RP-initiated logout; post_logout_redirect_uri must be registered for client_id")
    public ResponseEntity<Map<String, String>> logout(
            @RequestParam(value = "post_logout_redirect_uri", required = false) String postLogoutRedirectUri,
            @RequestParam(value = "id_token_hint", required = false) String idTokenHint,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "client_id", required = false) String clientId) {
        Optional<URI> target = oauth.logout(postLogoutRedirectUri, idTokenHint, state, clientId, RequestInfo.clientIp());
        if (target.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND).location(target.get()).build();
        }
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    @GetMapping(value = "/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "The realm's JSON Web Key Set (proxied)")
    public ResponseEntity<byte[]> jwks() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(oauth.jwks());
    }

    @PostMapping(value = "/introspect", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "RFC 7662 token introspection for an authenticated confidential client")
    public Map<String, Object> introspect(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(value = "token", required = false) String token,
            @RequestParam(value = "client_id", required = false) String clientId,
            @RequestParam(value = "client_secret", required = false) String clientSecret) {
        return oauth.introspect(token, ClientAuthenticator.presented(authorization, clientId, clientSecret));
    }

    /** RFC 6749 §5.2 body + the wrapper envelope. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> oauthError(ApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.error());
        body.put("error_description", e.getMessage());
        body.put("message", e.getMessage());
        body.put("details", e.details());
        ResponseEntity.BodyBuilder r = ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache");
        if (e.status() == HttpStatus.UNAUTHORIZED && ErrorCodes.INVALID_CLIENT.equals(e.error())) {
            r = r.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"oauth2\"");
        }
        return r.body(body);
    }

    private static String bearer(String authorization) {
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String t = authorization.substring(7).trim();
            return t.isEmpty() ? null : t;
        }
        return null;
    }
}
