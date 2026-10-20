package rw.gov.risa.sso.starter;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** An RSA key, a JWKS endpoint on localhost, and a token minter shaped like Keycloak's. */
public final class TestTokens implements AutoCloseable {

  public static final String ISSUER = "http://localhost:8080/realms/government-internal";

  private final RSAKey key;
  private final HttpServer server;

  public TestTokens() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("test-kid").generate();
    String body = new JWKSet(key.toPublicJWK()).toString();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/certs",
        ex -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, bytes.length);
          ex.getResponseBody().write(bytes);
          ex.close();
        });
    server.start();
  }

  public String jwksUri() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/certs";
  }

  /** A Keycloak-like access token for minagri-kubaka-web; customise claims with {@code c}. */
  public String rs256(Consumer<JWTClaimsSet.Builder> c) throws Exception {
    JWTClaimsSet.Builder b = baseClaims();
    c.accept(b);
    SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), b.build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  /** Same claims, HMAC-signed — must be refused. */
  public String hs256() throws Exception {
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), baseClaims().build());
    jwt.sign(new MACSigner("0123456789abcdef0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  public static JWTClaimsSet.Builder baseClaims() {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .subject("8c1f-user")
        .audience("account")
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(300)))
        .claim("azp", "minagri-kubaka-web")
        .claim("preferred_username", "jdoe")
        .claim("join_key", "1199080012345678")
        .claim("institution", "minagri")
        .claim("realm_access", Map.of("roles", List.of("inst:minagri:staff", "offline_access")))
        .claim("resource_access", Map.of("minagri-kubaka-web", Map.of("roles", List.of("viewer"))))
        .claim("scope", "openid profile email");
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
