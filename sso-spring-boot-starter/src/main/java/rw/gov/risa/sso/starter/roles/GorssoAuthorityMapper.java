package rw.gov.risa.sso.starter.roles;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * One source of authorities for a GoRSSO token. Every bean of this type is folded into
 * {@link GorssoAuthoritiesConverter}; declare your own bean to add app-specific authorities.
 */
@FunctionalInterface
public interface GorssoAuthorityMapper {
  Collection<GrantedAuthority> map(Jwt jwt);
}
