package rw.gov.risa.sso.starter.jwt;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import rw.gov.risa.sso.starter.config.GorssoProperties;

/** Builds the GoRSSO {@code JwtDecoder}: RS256 only, JWKS from properties, iss + exp/nbf + azp. */
public final class GorssoJwtDecoders {

  private GorssoJwtDecoders() {}

  public static NimbusJwtDecoder create(GorssoProperties props) {
    String jwks = props.effectiveJwkSetUri();
    if (jwks == null) {
      throw new IllegalStateException("GoRSSO: gorsso.issuer-uri is required");
    }
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
    decoder.setJwtValidator(validator(props));
    return decoder;
  }

  /** The validator chain, reusable by apps that build their own decoder. */
  public static OAuth2TokenValidator<Jwt> validator(GorssoProperties props) {
    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    validators.add(new Rs256OnlyValidator());
    validators.add(new JwtTimestampValidator(props.clockSkew()));
    if (props.issuerUri() == null || props.issuerUri().isBlank()) {
      throw new IllegalStateException("GoRSSO: gorsso.issuer-uri is required (the token iss is always checked)");
    }
    validators.add(new JwtIssuerValidator(props.issuerUri()));
    validators.add(new AzpValidator(props.effectiveAllowedAzp()));
    return new DelegatingOAuth2TokenValidator<>(validators);
  }
}
