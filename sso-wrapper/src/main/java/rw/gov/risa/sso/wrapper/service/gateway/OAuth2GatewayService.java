package rw.gov.risa.sso.wrapper.service.gateway;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.service.gateway.AuthCodeStore.StoredCode;
import rw.gov.risa.sso.wrapper.service.gateway.TokenGates.Door;

/**
 * {@code /oauth2/*} (port of {@code api/oauth2.py}): the browser authorize redirect, the password-flow
 * bridge ({@code authorize-internal}), the token endpoint, discovery, logout, JWKS and introspection.
 *
 * <p>Differences from the reference, all required by the spec: a confidential client must present its
 * own secret (the reference looked it up); the gates also run on a wrapper-issued code and on refresh,
 * and a token without {@code preferred_username} is 401 rather than "not gated"; a wrapper code is spent
 * by the first redemption attempt whatever its outcome; the redirect URI is checked at
 * {@code authorize-internal} too.
 */
@Service
public class OAuth2GatewayService {

    private static final Logger log = LoggerFactory.getLogger(OAuth2GatewayService.class);

    public static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
    public static final String GRANT_REFRESH_TOKEN = "refresh_token";
    public static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";
    public static final String DEFAULT_SCOPE = "openid profile email";

    private final SsoProperties sso;
    private final GatewayProperties props;
    private final GatewayDirectory directory;
    private final ClientAuthenticator clients;
    private final KeycloakOidc oidc;
    private final AuthCodeStore codes;
    private final TokenGates gates;
    private final AuditEmitter audit;

    public OAuth2GatewayService(SsoProperties sso, GatewayProperties props, GatewayDirectory directory,
                                ClientAuthenticator clients, KeycloakOidc oidc, AuthCodeStore codes, TokenGates gates,
                                AuditEmitter audit) {
        this.sso = sso;
        this.props = props;
        this.directory = directory;
        this.clients = clients;
        this.oidc = oidc;
        this.codes = codes;
        this.gates = gates;
        this.audit = audit;
    }

    // ---- GET /oauth2/authorize -------------------------------------------------------------

    /** The browser's authorization request, as received. */
    public record AuthorizeRequest(String clientId, String redirectUri, String responseType, String codeChallenge,
                                   String codeChallengeMethod, String scope, String state, String nonce,
                                   String acrValues, String prompt, String loginHint) {
    }

    /**
     * Validate the client, the redirect URI (exact match) and PKCE (S256 only), then the URL of Keycloak's
     * PUBLIC authorization endpoint with the request forwarded. Every refusal is a 400 shown to the browser
     * (never a redirect to an unvalidated URI).
     */
    public URI authorize(AuthorizeRequest r, String requestIp) {
        try {
            require(r.clientId(), "client_id");
            require(r.redirectUri(), "redirect_uri");
            require(r.responseType(), "response_type");
            if (!"code".equals(r.responseType())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, GatewayErrors.UNSUPPORTED_RESPONSE_TYPE,
                        "Unsupported response_type; only 'code' is allowed");
            }
            String method = blank(r.codeChallengeMethod()) ? Pkce.S256 : r.codeChallengeMethod();
            if (!Pkce.S256.equals(method)) {
                throw GatewayErrors.invalidRequest("Unsupported code_challenge_method; only 'S256' is allowed");
            }
            require(r.codeChallenge(), "code_challenge");
            if (!Pkce.isValidChallenge(r.codeChallenge())) {
                throw GatewayErrors.invalidRequest("code_challenge must be the base64url SHA-256 of the code_verifier");
            }
            RegisteredClient client = directory.findClient(r.clientId())
                    .filter(RegisteredClient::enabled).filter(c -> !c.bearerOnly())
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_CLIENT,
                            "Unknown or disabled client."));
            if (!RedirectUris.isRegistered(r.redirectUri(), client.redirectUris(), props.allowWildcardRedirects())) {
                audit.emit(AuditEvent.of(AuditEventType.AUTHORIZE_REDIRECT_DENIED).denied().clientId(r.clientId())
                        .requestIp(requestIp).put("redirect_uri", r.redirectUri()));
                throw GatewayErrors.invalidRequest("redirect_uri is not registered for this client");
            }
            String scope = blank(r.scope()) ? DEFAULT_SCOPE : r.scope();
            Map<String, String> q = new LinkedHashMap<>();
            q.put("client_id", r.clientId());
            q.put("redirect_uri", r.redirectUri());
            q.put("response_type", "code");
            q.put("scope", scope);
            q.put("code_challenge", r.codeChallenge());
            q.put("code_challenge_method", Pkce.S256);
            optional(q, "state", r.state());
            optional(q, "nonce", r.nonce());
            optional(q, "acr_values", r.acrValues());
            optional(q, "prompt", r.prompt());
            optional(q, "login_hint", r.loginHint());
            audit.emit(AuditEvent.of(AuditEventType.AUTHORIZE_START).clientId(r.clientId()).requestIp(requestIp)
                    .put("acr_values", r.acrValues()).put("scope", scope));
            return withQuery(sso.keycloak().publicRealmUrl() + "/protocol/openid-connect/auth", q);
        } catch (ApiException e) {
            if (!"redirect_uri is not registered for this client".equals(e.getMessage())) {
                audit.emit(AuditEvent.of(AuditEventType.AUTHORIZE_START).failure().clientId(r.clientId())
                        .requestIp(requestIp).put("error", e.error()).put("reason", e.getMessage()));
            }
            throw e;
        }
    }

    // ---- POST /oauth2/authorize-internal ---------------------------------------------------

    /** The password-flow bridge request (bearer = the access token from {@code /auth/login}). */
    public record InternalAuthorizeRequest(String clientId, String redirectUri, String refreshToken, String idToken,
                                           String codeChallenge, String codeChallengeMethod) {
    }

    /** Turn a {@code /auth/login} token into a wrapper code, after every gate. Returns the code. */
    public String authorizeInternal(String accessToken, InternalAuthorizeRequest r, String requestIp) {
        String username = null;
        try {
            if (blank(accessToken)) {
                throw ApiException.unauthorized("Bearer token required");
            }
            require(r.clientId(), "client_id");
            require(r.redirectUri(), "redirect_uri");
            if (blank(r.refreshToken())) {
                throw GatewayErrors.invalidRequest("refresh_token is required for authorization code generation");
            }
            if (blank(r.idToken())) {
                throw GatewayErrors.invalidRequest(
                        "id_token is required for authorization code generation (needed for logout)");
            }
            String method = blank(r.codeChallengeMethod()) ? Pkce.S256 : r.codeChallengeMethod();
            if (!Pkce.S256.equals(method)) {
                throw GatewayErrors.invalidRequest("Only code_challenge_method=S256 is supported");
            }
            if (!Pkce.isValidChallenge(r.codeChallenge())) {
                throw GatewayErrors.invalidRequest("code_challenge must be the base64url SHA-256 of the code_verifier");
            }
            RegisteredClient client = directory.findClient(r.clientId()).filter(RegisteredClient::enabled)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_CLIENT,
                            "Unknown or disabled client."));
            if (!RedirectUris.isRegistered(r.redirectUri(), client.redirectUris(), props.allowWildcardRedirects())) {
                throw GatewayErrors.invalidRequest("redirect_uri is not registered for this client");
            }
            TokenVerifier.Verified token = gates.pass(Door.AUTHORIZE_INTERNAL, accessToken, r.clientId(), requestIp);
            username = token.username();
            Map<String, Object> tokens = new LinkedHashMap<>();
            tokens.put("access_token", accessToken);
            tokens.put("refresh_token", r.refreshToken());
            tokens.put("id_token", r.idToken());
            tokens.put("token_type", AuthGatewayService.TOKEN_TYPE);
            String code = codes.issue(new StoredCode(tokens, r.clientId(), r.redirectUri(), r.codeChallenge(),
                    Pkce.S256, username));
            audit.emit(AuditEvent.of(AuditEventType.AUTHORIZE_INTERNAL_CODE).actor(username).clientId(r.clientId())
                    .session(token.sessionId()).requestIp(requestIp));
            return code;
        } catch (ApiException e) {
            audit.emit(AuditEvent.of(AuditEventType.AUTHORIZE_INTERNAL_CODE)
                    .outcome(e.status() == HttpStatus.FORBIDDEN ? "denied" : "failure").actor(username)
                    .clientId(r.clientId()).requestIp(requestIp).put("error", e.error())
                    .put("status", e.status().value()));
            throw e;
        }
    }

    // ---- POST /oauth2/token ----------------------------------------------------------------

    /** The token request form. */
    public record TokenRequest(String grantType, String code, String redirectUri, String refreshToken, String scope,
                               String codeVerifier) {
    }

    /** RFC 6749 token endpoint. Every outcome is audited (TOKEN_ISSUED / TOKEN_REFRESHED / TOKEN_REJECTED). */
    public Map<String, Object> token(TokenRequest r, ClientAuthenticator.Presented presented, String requestIp) {
        String grant = r.grantType();
        try {
            if (blank(grant)) {
                throw GatewayErrors.invalidRequest("grant_type is required");
            }
            if (!List.of(GRANT_AUTHORIZATION_CODE, GRANT_REFRESH_TOKEN, GRANT_CLIENT_CREDENTIALS).contains(grant)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.UNSUPPORTED_GRANT_TYPE,
                        "Unsupported grant type: " + grant);
            }
            RegisteredClient client = clients.authenticate(presented, GRANT_CLIENT_CREDENTIALS.equals(grant));
            return switch (grant) {
                case GRANT_AUTHORIZATION_CODE -> authorizationCode(r, client, presented.secret(), requestIp);
                case GRANT_REFRESH_TOKEN -> refresh(r, client, presented.secret(), requestIp);
                default -> clientCredentials(r, client, presented.secret(), requestIp);
            };
        } catch (ApiException e) {
            audit.emit(AuditEvent.of(AuditEventType.TOKEN_REJECTED)
                    .outcome(e.status() == HttpStatus.FORBIDDEN ? "denied" : "failure").clientId(presented.clientId())
                    .requestIp(requestIp).put("grant_type", grant).put("error", e.error())
                    .put("status", e.status().value()));
            throw e;
        }
    }

    private Map<String, Object> authorizationCode(TokenRequest r, RegisteredClient client, String secret,
                                                  String requestIp) {
        if (blank(r.code()) || blank(r.redirectUri())) {
            throw GatewayErrors.invalidRequest("Code and redirect_uri required for authorization_code grant");
        }
        if (blank(r.codeVerifier())) {
            throw GatewayErrors.invalidRequest("code_verifier is required for authorization_code grant (PKCE)");
        }
        // Spent NOW, before any check: a failed redemption still burns the code.
        Optional<StoredCode> stored = codes.consume(r.code());
        if (stored.isPresent()) {
            StoredCode c = stored.get();
            if (!client.clientId().equals(c.clientId()) || !r.redirectUri().equals(c.redirectUri())) {
                throw GatewayErrors.invalidGrant("Invalid client ID or redirect URI.");
            }
            if (!Pkce.verify(r.codeVerifier(), c.codeChallenge(), c.codeChallengeMethod())) {
                throw GatewayErrors.invalidGrant("PKCE verification failed");
            }
            Object access = c.tokens().get("access_token");
            TokenVerifier.Verified token = gates.pass(Door.TOKEN_WRAPPER_CODE,
                    access == null ? null : String.valueOf(access), client.clientId(), requestIp);
            Map<String, Object> out = new LinkedHashMap<>(c.tokens());
            Object exp = token.claims().get("exp");
            if (exp instanceof Instant at) {
                out.put("expires_in", Math.max(0, at.getEpochSecond() - Instant.now().getEpochSecond()));
            }
            audit.emit(AuditEvent.of(AuditEventType.TOKEN_ISSUED).actor(token.username()).clientId(client.clientId())
                    .session(token.sessionId()).requestIp(requestIp).put("grant_type", GRANT_AUTHORIZATION_CODE)
                    .put("source", "wrapper"));
            return out;
        }
        if (!Pkce.isValidVerifier(r.codeVerifier())) {
            throw GatewayErrors.invalidGrant("PKCE verification failed");
        }
        KeycloakOidc.Answer a = oidc.authorizationCode(client.clientId(), secret, r.code(), r.redirectUri(),
                r.codeVerifier());
        Map<String, Object> tokens = upstreamTokens(a, "Token exchange failed");
        TokenVerifier.Verified token = gateOrRevoke(Door.TOKEN_BROWSER_CODE, tokens, client, secret, requestIp);
        audit.emit(AuditEvent.of(AuditEventType.TOKEN_ISSUED).actor(token.username()).clientId(client.clientId())
                .session(token.sessionId()).requestIp(requestIp).put("grant_type", GRANT_AUTHORIZATION_CODE)
                .put("source", "keycloak"));
        return tokens;
    }

    private Map<String, Object> refresh(TokenRequest r, RegisteredClient client, String secret, String requestIp) {
        if (blank(r.refreshToken())) {
            throw GatewayErrors.invalidRequest("Refresh token required for refresh_token grant");
        }
        KeycloakOidc.Answer a = oidc.refresh(client.clientId(), secret, r.refreshToken());
        Map<String, Object> tokens = upstreamTokens(a, "Token refresh failed");
        // A revoked assignment, a key that became ambiguous or TOTP now required must not survive a refresh.
        TokenVerifier.Verified token = gateOrRevoke(Door.TOKEN_REFRESH, tokens, client, secret, requestIp);
        audit.emit(AuditEvent.of(AuditEventType.TOKEN_REFRESHED).actor(token.username()).clientId(client.clientId())
                .session(token.sessionId()).requestIp(requestIp).put("grant_type", GRANT_REFRESH_TOKEN));
        return tokens;
    }

    private Map<String, Object> clientCredentials(TokenRequest r, RegisteredClient client, String secret,
                                                  String requestIp) {
        KeycloakOidc.Answer a = oidc.clientCredentials(client.clientId(), secret, r.scope());
        Map<String, Object> tokens = upstreamTokens(a, "Token exchange failed");
        audit.emit(AuditEvent.of(AuditEventType.TOKEN_ISSUED).actor("service-account-" + client.clientId())
                .clientId(client.clientId()).requestIp(requestIp).put("grant_type", GRANT_CLIENT_CREDENTIALS));
        return tokens;
    }

    /** Run the gates on a token Keycloak just minted; on refusal end that session before answering. */
    private TokenVerifier.Verified gateOrRevoke(Door door, Map<String, Object> tokens, RegisteredClient client,
                                                String secret, String requestIp) {
        Object access = tokens.get("access_token");
        try {
            return gates.pass(door, access == null ? null : String.valueOf(access), client.clientId(), requestIp);
        } catch (ApiException e) {
            Object refresh = tokens.get("refresh_token");
            oidc.revoke(client.clientId(), secret, refresh == null ? null : String.valueOf(refresh));
            throw e;
        }
    }

    /** Keycloak's token answer, or its RFC 6749 refusal passed through (status and error code kept). */
    private static Map<String, Object> upstreamTokens(KeycloakOidc.Answer a, String fallback) {
        if (a.ok() && a.body().get("access_token") != null) {
            return new LinkedHashMap<>(a.body());
        }
        if (a.status() >= 500 || a.ok()) {
            throw GatewayErrors.upstream("Identity provider error");
        }
        String error = a.error() == null ? ErrorCodes.INVALID_GRANT : a.error();
        HttpStatus status = HttpStatus.resolve(a.status());
        if (status == null || !status.is4xxClientError()) {
            status = HttpStatus.BAD_REQUEST;
        }
        throw new ApiException(status, error, a.errorDescription() == null ? fallback : a.errorDescription());
    }

    // ---- discovery, logout, jwks, introspection ---------------------------------------------

    /** OpenID Provider metadata: wrapper endpoints, issuer = the issuer Keycloak puts in tokens. */
    public Map<String, Object> discovery() {
        String base = trim(sso.apiBaseUrl());
        String oauth = base + "/oauth2";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", sso.keycloak().issuer());
        m.put("authorization_endpoint", oauth + "/authorize");
        m.put("token_endpoint", oauth + "/token");
        m.put("userinfo_endpoint", base + "/auth/userinfo");
        m.put("jwks_uri", oauth + "/jwks");
        m.put("end_session_endpoint", oauth + "/logout");
        m.put("introspection_endpoint", oauth + "/introspect");
        m.put("response_types_supported", List.of("code"));
        m.put("subject_types_supported", List.of("public"));
        m.put("id_token_signing_alg_values_supported", List.of("RS256"));
        m.put("scopes_supported", List.of("openid", "profile", "email"));
        m.put("token_endpoint_auth_methods_supported", List.of("client_secret_post", "client_secret_basic"));
        m.put("introspection_endpoint_auth_methods_supported", List.of("client_secret_post", "client_secret_basic"));
        m.put("code_challenge_methods_supported", List.of(Pkce.S256));
        m.put("grant_types_supported", List.of(GRANT_AUTHORIZATION_CODE, GRANT_REFRESH_TOKEN, GRANT_CLIENT_CREDENTIALS));
        return m;
    }

    /**
     * RP-initiated logout: the Keycloak end-session URL to redirect to, or empty (nothing to forward). A
     * {@code post_logout_redirect_uri} needs {@code client_id} and must be one of the client's registered
     * post-logout URIs (no open redirect).
     */
    public Optional<URI> logout(String postLogoutRedirectUri, String idTokenHint, String state, String clientId,
                                String requestIp) {
        if (!blank(postLogoutRedirectUri)) {
            if (blank(clientId)) {
                throw GatewayErrors.invalidRequest("client_id is required when post_logout_redirect_uri is provided");
            }
            RegisteredClient client = directory.findClient(clientId).filter(RegisteredClient::enabled)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_CLIENT,
                            "Unknown or disabled client."));
            if (!RedirectUris.isRegistered(postLogoutRedirectUri, client.postLogoutRedirectUris(),
                    props.allowWildcardRedirects())) {
                audit.emit(AuditEvent.of(AuditEventType.LOGOUT_REDIRECT_DENIED).denied().clientId(clientId)
                        .requestIp(requestIp).put("post_logout_redirect_uri", postLogoutRedirectUri));
                throw GatewayErrors.invalidRequest("post_logout_redirect_uri is not registered for this client");
            }
        }
        audit.emit(AuditEvent.of(AuditEventType.LOGOUT).clientId(blank(clientId) ? null : clientId)
                .requestIp(requestIp).put("door", "/oauth2/logout"));
        Map<String, String> b = new LinkedHashMap<>();
        boolean any = optional(b, "post_logout_redirect_uri", postLogoutRedirectUri);
        any |= optional(b, "id_token_hint", idTokenHint);
        any |= optional(b, "state", state);
        any |= optional(b, "client_id", clientId);
        return any ? Optional.of(withQuery(sso.keycloak().publicRealmUrl() + "/protocol/openid-connect/logout", b))
                : Optional.empty();
    }

    /** The realm JWKS, proxied (raw JSON). */
    public byte[] jwks() {
        KeycloakOidc.Answer a = oidc.certs();
        if (!a.ok()) {
            log.error("gateway: JWKS fetch failed with HTTP {}", a.status());
            throw ApiException.badGateway(ErrorCodes.KEYCLOAK_ERROR, "Failed to fetch JWKS");
        }
        return a.raw();
    }

    /** RFC 7662, for an authenticated confidential client only. */
    public Map<String, Object> introspect(String token, ClientAuthenticator.Presented presented) {
        if (blank(token)) {
            throw GatewayErrors.invalidRequest("token is required");
        }
        RegisteredClient client = clients.authenticate(presented, true);
        KeycloakOidc.Answer a = oidc.introspect(client.clientId(), presented.secret(), token);
        if (!a.ok()) {
            log.warn("gateway: introspection failed with HTTP {}", a.status());
            if (a.status() >= 500) {
                throw GatewayErrors.upstream("Identity provider error");
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST, "Token introspection failed");
        }
        return a.body();
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static boolean optional(Map<String, String> query, String name, String value) {
        if (blank(value)) {
            return false;
        }
        query.put(name, value);
        return true;
    }

    /** {@code base?k=v&...}, each value form-encoded (a {@code +} or {@code {} in state survives intact). */
    static URI withQuery(String base, Map<String, String> query) {
        StringBuilder sb = new StringBuilder(base);
        char sep = base.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> e : query.entrySet()) {
            sb.append(sep).append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            sep = '&';
        }
        return URI.create(sb.toString());
    }

    private static void require(String value, String name) {
        if (blank(value)) {
            throw GatewayErrors.invalidRequest(name + " is required");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return s == null ? "" : s.replaceAll("/+$", "");
    }
}
