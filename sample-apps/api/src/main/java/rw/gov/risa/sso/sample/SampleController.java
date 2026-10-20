package rw.gov.risa.sso.sample;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rw.gov.risa.sso.starter.joinkey.JoinKey;
import rw.gov.risa.sso.starter.joinkey.JoinKeyValue;
import rw.gov.risa.sso.starter.roles.InstitutionRoles;

@RestController
@RequestMapping("/api")
public class SampleController {

  private final SampleProperties props;

  public SampleController(SampleProperties props) {
    this.props = props;
  }

  /** Open: lets the SPA show which ministry it is talking to before login. */
  @GetMapping("/public/info")
  public Map<String, String> info(InstitutionRoles none) {
    return Map.of("display_name", props.displayName(), "institution", String.valueOf(none.slug()));
  }

  /**
   * Who am I, as this app sees it. {@code join_key} is optional here so the page also works
   * for a client whose join column is not set yet.
   */
  @GetMapping("/me")
  public Me me(
      JwtAuthenticationToken auth,
      @JoinKey(required = false) JoinKeyValue joinKey,
      InstitutionRoles institution) {
    List<String> clientRoles =
        auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring("ROLE_".length()))
            .sorted()
            .toList();
    String username = auth.getToken().getClaimAsString("preferred_username");
    return new Me(
        username != null ? username : auth.getName(),
        auth.getToken().getClaimAsString("name"),
        auth.getToken().getClaimAsString("email"),
        joinKey == null ? null : joinKey.value(),
        joinKey == null ? null : joinKey.column().wireValue(),
        institution.slug(),
        List.copyOf(new TreeSet<>(institution.roles())),
        clientRoles);
  }

  /** Guarded by any {@code inst:<slug>:*} realm role of THIS app's institution. */
  @GetMapping("/records")
  @PreAuthorize("@gorsso.memberOfInstitution()")
  public Records records(InstitutionRoles institution) {
    return new Records(institution.slug(), props.displayName(), props.records());
  }

  public record Me(
      String username,
      String name,
      String email,
      @JsonProperty("join_key") String joinKey,
      @JsonProperty("join_column") String joinColumn,
      String institution,
      @JsonProperty("institution_roles") List<String> institutionRoles,
      @JsonProperty("client_roles") List<String> clientRoles) {}

  public record Records(
      String institution,
      @JsonProperty("display_name") String displayName,
      List<SampleProperties.DemoRecord> records) {}
}
