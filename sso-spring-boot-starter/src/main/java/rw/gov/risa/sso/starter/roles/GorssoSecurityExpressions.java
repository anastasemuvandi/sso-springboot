package rw.gov.risa.sso.starter.roles;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import rw.gov.risa.sso.starter.joinkey.JoinKeyResolver;

/**
 * Registered as bean {@code gorsso} for SpEL, e.g.
 * {@code @PreAuthorize("@gorsso.memberOfInstitution()")},
 * {@code @PreAuthorize("@gorsso.institutionRole('viewer')")},
 * {@code @PreAuthorize("@gorsso.institutionRole('inst:minagri:*')")}.
 */
public class GorssoSecurityExpressions {

  private final InstitutionRolesResolver institutions;
  private final JoinKeyResolver joinKeys;

  public GorssoSecurityExpressions(InstitutionRolesResolver institutions, JoinKeyResolver joinKeys) {
    this.institutions = institutions;
    this.joinKeys = joinKeys;
  }

  /** Any {@code inst:<slug>:*} role in the app's institution. */
  public boolean memberOfInstitution() {
    return institutions.current().isMember();
  }

  public boolean memberOf(String slug) {
    return institutions.current().isMemberOf(slug);
  }

  /** {@link InstitutionRoles#hasRole(String)} for the current caller. */
  public boolean institutionRole(String role) {
    return institutions.current().hasRole(role);
  }

  /** The token carries a usable {@code join_key}. */
  public boolean hasJoinKey() {
    if (joinKeys == null) {
      return false;
    }
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    Jwt jwt = InstitutionRolesResolver.jwtOf(a);
    return jwt != null && joinKeys.resolve(jwt).isPresent();
  }
}
