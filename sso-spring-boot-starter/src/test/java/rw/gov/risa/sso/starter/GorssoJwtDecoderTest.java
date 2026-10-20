package rw.gov.risa.sso.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import rw.gov.risa.sso.starter.config.GorssoProperties;
import rw.gov.risa.sso.starter.jwt.GorssoJwtDecoders;

class GorssoJwtDecoderTest {

  static TestTokens tokens;
  static JwtDecoder decoder;

  @BeforeAll
  static void setUp() throws Exception {
    tokens = new TestTokens();
    decoder = GorssoJwtDecoders.create(props(tokens.jwksUri(), "minagri-kubaka-web"));
  }

  @AfterAll
  static void tearDown() {
    tokens.close();
  }

  static GorssoProperties props(String jwks, String clientId) {
    var src = new MapConfigurationPropertySource();
    src.put("gorsso.issuer-uri", TestTokens.ISSUER);
    src.put("gorsso.jwk-set-uri", jwks);
    if (clientId != null) {
      src.put("gorsso.client-id", clientId);
    }
    return new Binder(src).bindOrCreate("gorsso", GorssoProperties.class);
  }

  @Test
  void acceptsRs256TokenForTheConfiguredClient() throws Exception {
    Jwt jwt = decoder.decode(tokens.rs256(b -> {}));
    assertThat(jwt.getClaimAsString("preferred_username")).isEqualTo("jdoe");
  }

  @Test
  void refusesHs256() {
    assertThatThrownBy(() -> decoder.decode(tokens.hs256())).isInstanceOf(JwtException.class);
  }

  @Test
  void refusesForeignAzp() throws Exception {
    String t = tokens.rs256(b -> b.claim("azp", "minisante-hmis"));
    assertThatThrownBy(() -> decoder.decode(t))
        .isInstanceOf(JwtValidationException.class)
        .hasMessageContaining("azp");
  }

  @Test
  void refusesMissingAzp() throws Exception {
    String t = tokens.rs256(b -> b.claim("azp", null));
    assertThatThrownBy(() -> decoder.decode(t)).isInstanceOf(JwtValidationException.class);
  }

  @Test
  void refusesWrongIssuer() throws Exception {
    String t = tokens.rs256(b -> b.issuer("http://evil/realms/government-internal"));
    assertThatThrownBy(() -> decoder.decode(t)).isInstanceOf(JwtValidationException.class);
  }

  @Test
  void refusesExpiredBeyondSkew() throws Exception {
    String t = tokens.rs256(b -> b.issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(Instant.now().minusSeconds(60))));
    assertThatThrownBy(() -> decoder.decode(t)).isInstanceOf(JwtValidationException.class);
  }

  @Test
  void allowedAzpListExtendsClientId() throws Exception {
    var src = new MapConfigurationPropertySource();
    src.put("gorsso.issuer-uri", TestTokens.ISSUER);
    src.put("gorsso.jwk-set-uri", tokens.jwksUri());
    src.put("gorsso.client-id", "minagri-kubaka-web");
    src.put("gorsso.allowed-azp[0]", "minagri-kubaka-mobile");
    GorssoProperties p = new Binder(src).bindOrCreate("gorsso", GorssoProperties.class);
    assertThat(p.effectiveAllowedAzp()).containsExactly("minagri-kubaka-mobile", "minagri-kubaka-web");
    JwtDecoder d = GorssoJwtDecoders.create(p);
    assertThat(d.decode(tokens.rs256(b -> b.claim("azp", "minagri-kubaka-mobile")))).isNotNull();
  }

  @Test
  void requiresAClientId() {
    assertThatThrownBy(() -> GorssoJwtDecoders.create(props(tokens.jwksUri(), null)))
        .hasMessageContaining("gorsso.client-id");
  }

  @Test
  void jwksDefaultsToKeycloakCertsUnderIssuer() {
    var src = new MapConfigurationPropertySource();
    src.put("gorsso.issuer-uri", TestTokens.ISSUER + "/");
    GorssoProperties p = new Binder(src).bindOrCreate("gorsso", GorssoProperties.class);
    assertThat(p.effectiveJwkSetUri()).isEqualTo(TestTokens.ISSUER + "/protocol/openid-connect/certs");
    assertThat(p.clockSkew()).hasSeconds(5);
    assertThat(p.resourceServer().permitAll()).contains("/actuator/health");
    assertThat(p.effectiveAllowedAzp()).isEqualTo(List.of());
  }
}
