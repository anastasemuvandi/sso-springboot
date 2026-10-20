package rw.gov.risa.sso.wrapper.service.gateway;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** A test RS256 signing key, its JWKS, and a minter for Keycloak-shaped access tokens. */
public final class GatewayTestKeys {

    public static final String REALM = "government-internal";
    public static final String KID = "test-kid";

    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER = generate();

    private GatewayTestKeys() {
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The realm JWKS JSON (public key only). */
    public static String jwks() {
        RSAKey k = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).keyID(KID).algorithm(JWSAlgorithm.RS256).build();
        return new JWKSet(k).toString();
    }

    /** Builder for a token: {@code Token.of(issuerBase).user("jdoe").client("app").sign()}. */
    public static Token token(String issuerBase) {
        return new Token(issuerBase);
    }

    public static final class Token {
        private final String issuerBase;
        private final Map<String, Object> claims = new LinkedHashMap<>();
        private boolean foreignKey;
        private long ttlSeconds = 300;

        private Token(String issuerBase) {
            this.issuerBase = issuerBase;
            claims.put("sub", UUID.randomUUID().toString());
            claims.put("sid", UUID.randomUUID().toString());
            claims.put("realm_access", Map.of("roles", List.of("default-roles-government-internal")));
            claims.put("acr", "1");
        }

        public Token user(String username) {
            claims.put("preferred_username", username);
            claims.put("email", username + "@minagri.gov.rw");
            return this;
        }

        public Token client(String clientId) {
            claims.put("azp", clientId);
            return this;
        }

        public Token claim(String name, Object value) {
            if (value == null) {
                claims.remove(name);
            } else {
                claims.put(name, value);
            }
            return this;
        }

        public Token roles(String... realmRoles) {
            claims.put("realm_access", Map.of("roles", List.of(realmRoles)));
            return this;
        }

        public Token signedByAnotherKey() {
            foreignKey = true;
            return this;
        }

        public Token expired() {
            ttlSeconds = -600;
            return this;
        }

        public String sign() {
            try {
                Instant now = Instant.now();
                JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                        .issuer(issuerBase + "/realms/" + REALM)
                        .issueTime(Date.from(now.minusSeconds(5)))
                        .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                        .jwtID(UUID.randomUUID().toString());
                claims.forEach(b::claim);
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID)
                        .type(JOSEObjectType.JWT).build(), b.build());
                jwt.sign(new RSASSASigner((RSAPrivateKey) (foreignKey ? OTHER : KEYS).getPrivate()));
                return jwt.serialize();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
