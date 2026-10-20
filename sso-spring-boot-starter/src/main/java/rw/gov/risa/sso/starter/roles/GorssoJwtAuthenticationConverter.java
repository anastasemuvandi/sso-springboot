package rw.gov.risa.sso.starter.roles;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Jwt → {@link JwtAuthenticationToken} with GoRSSO authorities; the principal name is
 * {@code preferred_username} (falling back to {@code sub}). Plug it into your own chain with
 * {@code .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))}.
 */
public final class GorssoJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  private final GorssoAuthoritiesConverter authorities;

  public GorssoJwtAuthenticationConverter(GorssoAuthoritiesConverter authorities) {
    this.authorities = authorities;
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    String name = jwt.getClaimAsString("preferred_username");
    if (name == null || name.isBlank()) {
      name = jwt.getSubject();
    }
    return new JwtAuthenticationToken(jwt, authorities.convert(jwt), name);
  }
}
