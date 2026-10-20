package rw.gov.risa.sso.starter.jwt;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Accepts a token only when its {@code azp} (the client the token was issued to) is this app's
 * client id or one of the explicitly allowed ones. Keycloak access tokens carry {@code aud=account}
 * by default, so {@code azp} is the claim that says "this token was minted for us".
 */
public final class AzpValidator implements OAuth2TokenValidator<Jwt> {

  private final List<String> allowed;

  public AzpValidator(List<String> allowed) {
    if (allowed == null || allowed.isEmpty()) {
      throw new IllegalArgumentException(
          "GoRSSO: set gorsso.client-id (or gorsso.allowed-azp) — tokens are only accepted for a known azp");
    }
    this.allowed = List.copyOf(allowed);
  }

  public List<String> allowed() {
    return allowed;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    String azp = jwt.getClaimAsString("azp");
    if (azp != null && allowed.contains(azp)) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN,
            "The token was not issued to an allowed client (azp)",
            null));
  }
}
