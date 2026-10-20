package rw.gov.risa.sso.starter.roles;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Realm roles {@code inst:<slug>:<role>} → {@code INST_<slug>_<role>} (default prefix). Legacy
 * UPPERCASE slugs are lower-cased (slugs are canonical lowercase, spec §3). Other realm roles are
 * ignored: platform tiers (admin, super_admin, ...) mean nothing to an integrated app.
 */
public final class InstitutionRoleMapper implements GorssoAuthorityMapper {

  private final String prefix;

  public InstitutionRoleMapper(String prefix) {
    this.prefix = prefix;
  }

  @Override
  public Collection<GrantedAuthority> map(Jwt jwt) {
    Set<GrantedAuthority> out = new LinkedHashSet<>();
    for (String realmRole : TokenClaims.realmRoles(jwt)) {
      InstitutionRole.parse(realmRole)
          .ifPresent(r -> out.add(new SimpleGrantedAuthority(authority(prefix, r.slug(), r.role()))));
    }
    return out;
  }

  public static String authority(String prefix, String slug, String role) {
    return prefix + slug + "_" + role;
  }
}
