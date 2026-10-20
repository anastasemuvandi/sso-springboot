package rw.gov.risa.sso.sample;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import rw.gov.risa.sso.starter.roles.GorssoAuthoritiesConverter;

class SampleApiTest {

  static JwtRequestPostProcessor caller(
      GorssoAuthoritiesConverter authorities, String clientId, List<String> realmRoles, List<String> clientRoles, String joinKey) {
    return jwt()
        .jwt(j -> {
          j.claim("preferred_username", "jdoe").claim("name", "Jean Doe").claim("azp", clientId)
              .claim("realm_access", Map.of("roles", realmRoles))
              .claim("resource_access", Map.of(clientId, Map.of("roles", clientRoles)));
          if (joinKey != null) {
            j.claim("join_key", joinKey);
          }
        })
        .authorities(authorities);
  }

  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  @ActiveProfiles("minagri")
  class Minagri {
    @Autowired MockMvc mvc;
    @Autowired GorssoAuthoritiesConverter authorities;

    @Test
    void anonymousGets401ButPublicInfoIsOpen() throws Exception {
      mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
      mvc.perform(get("/api/records")).andExpect(status().isUnauthorized());
      mvc.perform(get("/api/public/info"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.institution").value("minagri"));
      mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void meReportsJoinKeyInstitutionAndClientRoles() throws Exception {
      mvc.perform(get("/api/me").with(caller(authorities, "minagri-kubaka-web",
              List.of("inst:minagri:staff", "offline_access"), List.of("viewer", "editor"), "1199080012345678")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.username").value("jdoe"))
          .andExpect(jsonPath("$.join_key").value("1199080012345678"))
          .andExpect(jsonPath("$.join_column").value("nid"))
          .andExpect(jsonPath("$.institution").value("minagri"))
          .andExpect(jsonPath("$.institution_roles[0]").value("staff"))
          .andExpect(jsonPath("$.client_roles[0]").value("editor"))
          .andExpect(jsonPath("$.client_roles[1]").value("viewer"));
    }

    @Test
    void meWithoutJoinKeyStillAnswers() throws Exception {
      mvc.perform(get("/api/me").with(caller(authorities, "minagri-kubaka-web", List.of(), List.of(), null)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.join_key").doesNotExist());
    }

    @Test
    void recordsNeedAnInstMinagriRole() throws Exception {
      mvc.perform(get("/api/records").with(caller(authorities, "minagri-kubaka-web", List.of("inst:minagri:hr"), List.of(), "x")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.institution").value("minagri"))
          .andExpect(jsonPath("$.records.length()").value(3));
      // a member of another ministry, or a platform admin with no institution role, is refused
      mvc.perform(get("/api/records").with(caller(authorities, "minagri-kubaka-web", List.of("inst:minisante:staff"), List.of("viewer"), "x")))
          .andExpect(status().isForbidden());
      mvc.perform(get("/api/records").with(caller(authorities, "minagri-kubaka-web", List.of("super_admin"), List.of(), "x")))
          .andExpect(status().isForbidden());
    }
  }

  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  @ActiveProfiles("minisante")
  class Minisante {
    @Autowired MockMvc mvc;
    @Autowired GorssoAuthoritiesConverter authorities;

    @Test
    void sameAppOtherMinistry() throws Exception {
      mvc.perform(get("/api/records").with(caller(authorities, "minisante-hmis", List.of("inst:minisante:staff"), List.of("clinician"), "a@moh.gov.rw")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.institution").value("minisante"));
      mvc.perform(get("/api/me").with(caller(authorities, "minisante-hmis", List.of("inst:minisante:staff"), List.of("clinician"), "A@MOH.gov.rw")))
          .andExpect(jsonPath("$.join_column").value("email"))
          .andExpect(jsonPath("$.join_key").value("a@moh.gov.rw"))
          .andExpect(jsonPath("$.client_roles[0]").value("clinician"));
      mvc.perform(get("/api/records").with(caller(authorities, "minisante-hmis", List.of("inst:minagri:staff"), List.of(), "x")))
          .andExpect(status().isForbidden());
    }
  }
}
