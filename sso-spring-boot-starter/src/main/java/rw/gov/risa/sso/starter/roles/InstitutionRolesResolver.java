package rw.gov.risa.sso.starter.roles;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Builds {@link InstitutionRoles} for a token or for the current request. */
public class InstitutionRolesResolver {

  private final String configuredSlug;

  public InstitutionRolesResolver(String configuredSlug) {
    this.configuredSlug = configuredSlug;
  }

  public String configuredSlug() {
    return configuredSlug;
  }

  public InstitutionRoles resolve(Jwt jwt) {
    return jwt == null ? InstitutionRoles.empty(configuredSlug) : InstitutionRoles.from(jwt, configuredSlug);
  }

  public InstitutionRoles resolve(Authentication authentication) {
    return resolve(jwtOf(authentication));
  }

  /** For the authenticated caller of this request; no roles (but the configured slug) if anonymous. */
  public InstitutionRoles current() {
    return resolve(SecurityContextHolder.getContext().getAuthentication());
  }

  static Jwt jwtOf(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken t) {
      return t.getToken();
    }
    if (authentication != null && authentication.getPrincipal() instanceof Jwt j) {
      return j;
    }
    return null;
  }
}
