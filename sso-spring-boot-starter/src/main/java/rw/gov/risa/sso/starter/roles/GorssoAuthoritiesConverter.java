package rw.gov.risa.sso.starter.roles;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Union of every {@link GorssoAuthorityMapper}. Also usable in tests:
 * {@code jwt().authorities(gorssoAuthoritiesConverter)}.
 */
public final class GorssoAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

  private final List<GorssoAuthorityMapper> mappers;

  public GorssoAuthoritiesConverter(List<GorssoAuthorityMapper> mappers) {
    this.mappers = List.copyOf(mappers);
  }

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    Set<GrantedAuthority> out = new LinkedHashSet<>();
    for (GorssoAuthorityMapper mapper : mappers) {
      Collection<GrantedAuthority> mapped = mapper.map(jwt);
      if (mapped != null) {
        out.addAll(mapped);
      }
    }
    return out;
  }
}
