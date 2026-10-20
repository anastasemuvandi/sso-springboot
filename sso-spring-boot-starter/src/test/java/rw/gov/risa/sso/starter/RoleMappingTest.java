package rw.gov.risa.sso.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import rw.gov.risa.sso.starter.roles.ClientRoleMapper;
import rw.gov.risa.sso.starter.roles.GorssoAuthoritiesConverter;
import rw.gov.risa.sso.starter.roles.GorssoJwtAuthenticationConverter;
import rw.gov.risa.sso.starter.roles.InstitutionRoleMapper;
import rw.gov.risa.sso.starter.roles.InstitutionRoles;
import rw.gov.risa.sso.starter.roles.ScopeAuthorityMapper;

class RoleMappingTest {

  static Jwt jwt(Map<String, Object> claims) {
    Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    b.claim("sub", "u1");
    claims.forEach(b::claim);
    return b.build();
  }

  static List<String> names(java.util.Collection<GrantedAuthority> a) {
    return a.stream().map(GrantedAuthority::getAuthority).toList();
  }

  @Test
  void institutionRolesBecomeInstAuthoritiesAndLegacyUppercaseIsNormalised() {
    Jwt j = jwt(Map.of("realm_access", Map.of("roles", List.of(
        "inst:minagri:staff", "inst:MINAGRI:hr", "inst:minisante:auditor", "admin", "offline_access", "inst:bad"))));
    assertThat(names(new InstitutionRoleMapper("INST_").map(j)))
        .containsExactly("INST_minagri_staff", "INST_minagri_hr", "INST_minisante_auditor");
  }

  @Test
  void clientRolesOfConfiguredClientsOnly() {
    Jwt j = jwt(Map.of("resource_access", Map.of(
        "minagri-kubaka-web", Map.of("roles", List.of("viewer", "editor")),
        "minisante-hmis", Map.of("roles", List.of("clinician")))));
    assertThat(names(new ClientRoleMapper(List.of("minagri-kubaka-web"), "ROLE_").map(j)))
        .containsExactly("ROLE_viewer", "ROLE_editor");
  }

  @Test
  void converterUnionsMappersAndNamesByPreferredUsername() {
    Jwt j = jwt(Map.of(
        "preferred_username", "jdoe",
        "scope", "openid email",
        "realm_access", Map.of("roles", List.of("inst:minagri:staff")),
        "resource_access", Map.of("minagri-kubaka-web", Map.of("roles", List.of("viewer")))));
    var converter = new GorssoAuthoritiesConverter(List.of(
        new InstitutionRoleMapper("INST_"), new ClientRoleMapper(List.of("minagri-kubaka-web"), "ROLE_"), new ScopeAuthorityMapper()));
    var auth = new GorssoJwtAuthenticationConverter(converter).convert(j);
    assertThat(auth.getName()).isEqualTo("jdoe");
    assertThat(names(auth.getAuthorities()))
        .containsExactlyInAnyOrder("INST_minagri_staff", "ROLE_viewer", "SCOPE_openid", "SCOPE_email");
  }

  @Test
  void nameFallsBackToSub() {
    var auth = new GorssoJwtAuthenticationConverter(new GorssoAuthoritiesConverter(List.of())).convert(jwt(Map.of()));
    assertThat(auth.getName()).isEqualTo("u1");
  }

  @Test
  void institutionRolesSlugPrecedence() {
    Jwt j = jwt(Map.of(
        "institution", "MINISANTE",
        "realm_access", Map.of("roles", List.of("inst:minagri:staff", "inst:minisante:hr"))));
    assertThat(InstitutionRoles.from(j, "minagri").slug()).isEqualTo("minagri");
    assertThat(InstitutionRoles.from(j, null).slug()).isEqualTo("minisante");
    Jwt single = jwt(Map.of("realm_access", Map.of("roles", List.of("inst:minagri:staff"))));
    assertThat(InstitutionRoles.from(single, null).slug()).isEqualTo("minagri");
    Jwt two = jwt(Map.of("realm_access", Map.of("roles", List.of("inst:minagri:staff", "inst:rdb:staff"))));
    assertThat(InstitutionRoles.from(two, null).slug()).isNull();
    assertThat(InstitutionRoles.from(two, null).isMember()).isFalse();
  }

  @Test
  void hasRoleForms() {
    Jwt j = jwt(Map.of("realm_access", Map.of("roles", List.of("inst:minagri:staff", "inst:minagri:hr", "inst:rdb:auditor"))));
    InstitutionRoles r = InstitutionRoles.from(j, "minagri");
    assertThat(r.hasRole("staff")).isTrue();
    assertThat(r.hasRole("admin")).isFalse();
    assertThat(r.hasRole("inst:minagri:hr")).isTrue();
    assertThat(r.hasRole("inst:rdb:auditor")).isTrue();
    assertThat(r.hasRole("inst:minagri:*")).isTrue();
    assertThat(r.hasRole("inst:minisante:*")).isFalse();
    assertThat(r.hasRole("*")).isTrue();
    assertThat(r.hasRole("inst:minagri")).isFalse();
    assertThat(r.hasRole(null)).isFalse();
    assertThat(r.hasAnyRole("admin", "hr")).isTrue();
    assertThat(r.roles()).containsExactly("staff", "hr");
    assertThat(r.realmRoles()).containsExactly("inst:minagri:staff", "inst:minagri:hr");
    assertThat(r.institutions()).containsExactly("minagri", "rdb");
  }

  @Test
  void anonymousViewKeepsTheConfiguredSlug() {
    var r = new rw.gov.risa.sso.starter.roles.InstitutionRolesResolver("MINAGRI").resolve((Jwt) null);
    assertThat(r.slug()).isEqualTo("minagri");
    assertThat(r.isMember()).isFalse();
    assertThat(new rw.gov.risa.sso.starter.roles.InstitutionRolesResolver(null).resolve((Jwt) null).slug()).isNull();
  }

  @Test
  void noRolesNoMembership() {
    InstitutionRoles r = InstitutionRoles.from(jwt(Map.of()), "minagri");
    assertThat(r.slug()).isEqualTo("minagri");
    assertThat(r.isMember()).isFalse();
    assertThat(r.hasRole("*")).isFalse();
  }
}
