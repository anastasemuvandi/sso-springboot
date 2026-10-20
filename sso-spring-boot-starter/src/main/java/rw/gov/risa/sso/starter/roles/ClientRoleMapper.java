package rw.gov.risa.sso.starter.roles;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** {@code resource_access.<clientId>.roles} → {@code ROLE_<role>} for the configured clients. */
public final class ClientRoleMapper implements GorssoAuthorityMapper {

  private final List<String> clientIds;
  private final String prefix;

  public ClientRoleMapper(List<String> clientIds, String prefix) {
    this.clientIds = List.copyOf(clientIds);
    this.prefix = prefix;
  }

  @Override
  public Collection<GrantedAuthority> map(Jwt jwt) {
    Set<GrantedAuthority> out = new LinkedHashSet<>();
    for (String clientId : clientIds) {
      for (String role : TokenClaims.clientRoles(jwt, clientId)) {
        out.add(new SimpleGrantedAuthority(prefix + role));
      }
    }
    return out;
  }
}
