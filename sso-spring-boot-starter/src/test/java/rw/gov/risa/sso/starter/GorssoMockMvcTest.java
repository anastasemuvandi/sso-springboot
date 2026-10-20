package rw.gov.risa.sso.starter;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.gov.risa.sso.starter.joinkey.JoinKey;
import rw.gov.risa.sso.starter.joinkey.JoinKeyValue;
import rw.gov.risa.sso.starter.roles.GorssoAuthoritiesConverter;
import rw.gov.risa.sso.starter.roles.InstitutionRoles;

@SpringBootTest(classes = GorssoMockMvcTest.App.class)
@AutoConfigureMockMvc
class GorssoMockMvcTest {

  static final TestTokens TOKENS;

  static {
    try {
      TOKENS = new TestTokens();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("gorsso.issuer-uri", () -> TestTokens.ISSUER);
    r.add("gorsso.jwk-set-uri", TOKENS::jwksUri);
    r.add("gorsso.client-id", () -> "minagri-kubaka-web");
    r.add("gorsso.institution.slug", () -> "minagri");
    r.add("gorsso.join-key.column", () -> "nid");
    r.add("gorsso.resource-server.permit-all[0]", () -> "/public/**");
  }

  @AfterAll
  static void stop() {
    TOKENS.close();
  }

  @Autowired MockMvc mvc;
  @Autowired GorssoAuthoritiesConverter authorities;

  /** jwt() with the claims of a minagri staff member + the app's real authority mapping. */
  JwtRequestPostProcessor minagriStaff() {
    return jwt()
        .jwt(j -> j.claim("preferred_username", "jdoe")
            .claim("join_key", "1199080012345678")
            .claim("realm_access", Map.of("roles", List.of("inst:minagri:staff")))
            .claim("resource_access", Map.of("minagri-kubaka-web", Map.of("roles", List.of("viewer")))))
        .authorities(authorities);
  }

  @Test
  void anonymousIs401() throws Exception {
    mvc.perform(get("/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void permitAllPathsAreOpen() throws Exception {
    mvc.perform(get("/public/ping")).andExpect(status().isOk());
  }

  @Test
  void meResolvesJoinKeyAndInstitutionRoles() throws Exception {
    mvc.perform(get("/me").with(minagriStaff()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.joinKey").value("1199080012345678"))
        .andExpect(jsonPath("$.column").value("nid"))
        .andExpect(jsonPath("$.slug").value("minagri"))
        .andExpect(jsonPath("$.roles[0]").value("staff"))
        .andExpect(jsonPath("$.staff").value(true));
  }

  @Test
  void missingJoinKeyIs403InTheErrorEnvelope() throws Exception {
    mvc.perform(get("/me").with(jwt().jwt(j -> j.claim("preferred_username", "x")).authorities(authorities)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("join_key_missing"));
  }

  @Test
  void optionalJoinKeyIsNull() throws Exception {
    mvc.perform(get("/optional").with(jwt().authorities(authorities)))
        .andExpect(status().isOk())
        .andExpect(content().string("none"));
  }

  @Test
  void institutionMembershipGuard() throws Exception {
    mvc.perform(get("/records").with(minagriStaff())).andExpect(status().isOk());
    mvc.perform(get("/records")
            .with(jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("inst:minisante:staff")))).authorities(authorities)))
        .andExpect(status().isForbidden());
  }

  @Test
  void instAndClientRoleAuthorities() throws Exception {
    mvc.perform(get("/inst-authority").with(minagriStaff())).andExpect(status().isOk());
    mvc.perform(get("/viewer").with(minagriStaff())).andExpect(status().isOk());
    mvc.perform(get("/editor").with(minagriStaff())).andExpect(status().isForbidden());
  }

  @Test
  void realBearerTokenGoesThroughTheRs256Decoder() throws Exception {
    mvc.perform(get("/me").header("Authorization", "Bearer " + TOKENS.rs256(b -> {})))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("jdoe"))
        .andExpect(jsonPath("$.slug").value("minagri"));
    mvc.perform(get("/viewer").header("Authorization", "Bearer " + TOKENS.rs256(b -> {})))
        .andExpect(status().isOk());
  }

  @Test
  void hs256AndForeignAzpAre401() throws Exception {
    mvc.perform(get("/me").header("Authorization", "Bearer " + TOKENS.hs256()))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/me").header("Authorization", "Bearer " + TOKENS.rs256(b -> b.claim("azp", "minisante-hmis"))))
        .andExpect(status().isUnauthorized());
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableMethodSecurity
  @Import(TestController.class)
  static class App {}

  @RestController
  static class TestController {

    @GetMapping("/me")
    Map<String, Object> me(
        org.springframework.security.core.Authentication auth,
        @JoinKey String joinKey,
        @JoinKey JoinKeyValue value,
        InstitutionRoles inst) {
      return Map.of(
          "username", auth.getName(),
          "joinKey", joinKey,
          "column", value.column().wireValue(),
          "slug", inst.slug(),
          "roles", List.copyOf(inst.roles()),
          "staff", inst.hasRole("staff"));
    }

    @GetMapping("/public/ping")
    String ping() {
      return "pong";
    }

    @GetMapping("/optional")
    String optional(@JoinKey(required = false) String joinKey) {
      return joinKey == null ? "none" : "some";
    }

    @GetMapping("/records")
    @PreAuthorize("@gorsso.memberOfInstitution()")
    String records() {
      return "[]";
    }

    @GetMapping("/inst-authority")
    @PreAuthorize("hasAuthority('INST_minagri_staff')")
    String inst() {
      return "ok";
    }

    @GetMapping("/viewer")
    @PreAuthorize("hasRole('viewer')")
    String viewer() {
      return "ok";
    }

    @GetMapping("/editor")
    @PreAuthorize("hasRole('editor')")
    String editor() {
      return "ok";
    }
  }
}
