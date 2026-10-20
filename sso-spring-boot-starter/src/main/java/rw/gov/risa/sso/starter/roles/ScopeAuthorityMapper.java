package rw.gov.risa.sso.starter.roles;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** {@code scope} → {@code SCOPE_<scope>} (Spring Security's default behaviour, kept). */
public final class ScopeAuthorityMapper implements GorssoAuthorityMapper {

  private final JwtGrantedAuthoritiesConverter delegate = new JwtGrantedAuthoritiesConverter();

  @Override
  public Collection<GrantedAuthority> map(Jwt jwt) {
    return delegate.convert(jwt);
  }
}
