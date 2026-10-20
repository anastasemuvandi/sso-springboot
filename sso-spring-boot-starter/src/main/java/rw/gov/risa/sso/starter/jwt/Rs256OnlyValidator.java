package rw.gov.risa.sso.starter.jwt;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Belt and braces: the decoder's key selector is already restricted to RS256, this re-checks the
 * header so a custom decoder wired with {@link GorssoJwtDecoders#validator} cannot drift.
 */
public final class Rs256OnlyValidator implements OAuth2TokenValidator<Jwt> {

  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    Object alg = jwt.getHeaders().get("alg");
    if (alg != null && "RS256".equals(alg.toString())) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Only RS256-signed tokens are accepted", null));
  }
}
