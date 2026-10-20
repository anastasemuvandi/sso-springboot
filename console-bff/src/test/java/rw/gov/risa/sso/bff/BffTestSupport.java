package rw.gov.risa.sso.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.bff.config.BffProperties;

/**
 * Shared fixture: a WireMock stand-in for the wrapper (token, jwks, and the /api upstream), an RSA
 * key that signs id/access tokens, a built SPA in a temp dir, and a helper that puts a logged-in
 * session (security context + authorized client) into a MockHttpSession.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("memory-session")
public abstract class BffTestSupport {
    public static final WireMockServer WRAPPER = new WireMockServer(wireMockConfig().dynamicPort());
    public static final String PUBLIC_WRAPPER = "https://sso.example.test";
    public static final String SECRET = "test-secret";
    public static final RSAKey KEY;
    public static final Path SPA_DIR;

    static {
        WRAPPER.start();
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-kid").generate();
            SPA_DIR = Files.createTempDirectory("console-web-dist");
            Files.writeString(SPA_DIR.resolve("index.html"), "<!doctype html><title>console</title><div id=root>");
            Files.createDirectories(SPA_DIR.resolve("assets"));
            Files.writeString(SPA_DIR.resolve("assets/app-abc123.js"), "console.log('app')");
            Files.writeString(SPA_DIR.resolve("favicon.svg"), "<svg/>");
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("bff.wrapper-url", WRAPPER::baseUrl);
        r.add("bff.wrapper-public-url", () -> PUBLIC_WRAPPER);
        r.add("bff.client-secret", () -> SECRET);
        r.add("bff.spa-dir", SPA_DIR::toString);
        r.add("bff.post-logout-redirect-uri", () -> "https://console.example.test/");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ClientRegistrationRepository registrations;
    @Autowired protected OAuth2AuthorizedClientRepository authorizedClients;

    @BeforeEach
    void resetWrapper() {
        WRAPPER.resetAll();
        WRAPPER.stubFor(get(urlEqualTo("/oauth2/jwks")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(new JWKSet(KEY.toPublicJWK()).toString())));
    }

    public static String jwt(Map<String, Object> claims) {
        try {
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder();
            claims.forEach((k, v) -> b.claim(k, v instanceof Instant i ? Date.from(i) : v));
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                    b.build());
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String accessToken(String username, List<String> roles, Instant exp) {
        return jwt(Map.of("sub", "sub-" + username, "preferred_username", username, "name", "Test " + username,
                "email", username + "@example.test", "realm_access", Map.of("roles", roles),
                "azp", "sso-admin-ui", "iat", exp.minusSeconds(300), "exp", exp));
    }

    /** A session that looks exactly like one oauth2Login leaves behind. */
    protected MockHttpSession loggedIn(String accessToken, Instant issuedAt, Instant expiresAt, String refreshToken) {
        MockHttpSession session = new MockHttpSession();
        OidcIdToken idToken = new OidcIdToken("id-token-of-alice", issuedAt, issuedAt.plusSeconds(300),
                Map.of("sub", "sub-alice", "preferred_username", "alice"));
        DefaultOidcUser user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken, "sub");
        OAuth2AuthenticationToken auth = new OAuth2AuthenticationToken(user, user.getAuthorities(),
                BffProperties.REGISTRATION_ID);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(auth));
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(
                registrations.findByRegistrationId(BffProperties.REGISTRATION_ID), auth.getName(),
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, accessToken, issuedAt, expiresAt),
                refreshToken == null ? null : new OAuth2RefreshToken(refreshToken, issuedAt));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        authorizedClients.saveAuthorizedClient(client, auth, request, new MockHttpServletResponse());
        return session;
    }

    protected MockHttpSession freshSession(String username, List<String> roles) {
        Instant now = Instant.now();
        return loggedIn(accessToken(username, roles, now.plusSeconds(300)), now, now.plusSeconds(300), "refresh-1");
    }
}
