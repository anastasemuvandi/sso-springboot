package rw.gov.risa.sso.starter.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code gorsso.*} — configuration for an app integrated with GoRSSO.
 *
 * <p>Minimum: {@code gorsso.issuer-uri} (the Keycloak realm issuer the wrapper's tokens carry,
 * e.g. {@code http://localhost:8080/realms/government-internal}) and {@code gorsso.client-id}
 * (the app's client, e.g. {@code minagri-kubaka-web}). The issuer is only <em>compared</em> to
 * the token's {@code iss}; {@code gorsso.jwk-set-uri} is what is <em>fetched</em> and may point at
 * a different (internal) host. When it is unset it defaults to the Keycloak certs endpoint under
 * the issuer.
 *
 * @param enabled        master switch for every GoRSSO auto-configuration
 * @param issuerUri      expected {@code iss}
 * @param jwkSetUri      JWKS URL (default {@code <issuer>/protocol/openid-connect/certs})
 * @param clientId       this app's client id; the default {@code azp} allow-list and the client
 *                       whose {@code resource_access.<id>.roles} become {@code ROLE_*}
 * @param allowedAzp     extra/explicit {@code azp} values accepted (e.g. a SPA and its mobile twin)
 * @param clockSkew      tolerated clock skew for {@code exp}/{@code nbf}
 * @param resourceServer the JWT decoder + default filter chain
 * @param joinKey        the {@code join_key} claim resolver
 * @param roles          authority mapping
 * @param institution    the app's own institution
 */
@ConfigurationProperties(prefix = "gorsso")
public record GorssoProperties(
    @DefaultValue("true") boolean enabled,
    String issuerUri,
    String jwkSetUri,
    String clientId,
    @DefaultValue List<String> allowedAzp,
    @DefaultValue("5s") Duration clockSkew,
    @DefaultValue ResourceServer resourceServer,
    @DefaultValue JoinKey joinKey,
    @DefaultValue Roles roles,
    @DefaultValue Institution institution) {

  /** Keycloak's JWKS path under a realm issuer. */
  public static final String KEYCLOAK_CERTS_PATH = "/protocol/openid-connect/certs";

  /** The JWKS URL to fetch: explicit, or derived from the issuer. */
  public String effectiveJwkSetUri() {
    if (jwkSetUri != null && !jwkSetUri.isBlank()) {
      return jwkSetUri;
    }
    if (issuerUri == null || issuerUri.isBlank()) {
      return null;
    }
    return stripTrailingSlash(issuerUri) + KEYCLOAK_CERTS_PATH;
  }

  /** {@code azp} values accepted: {@code allowed-azp} plus {@code client-id}. */
  public List<String> effectiveAllowedAzp() {
    List<String> out = new ArrayList<>(allowedAzp == null ? List.of() : allowedAzp);
    if (clientId != null && !clientId.isBlank() && !out.contains(clientId)) {
      out.add(clientId);
    }
    return List.copyOf(out);
  }

  /** Clients whose {@code resource_access.<id>.roles} are mapped to {@code ROLE_*}. */
  public List<String> effectiveClientRoleClients() {
    List<String> configured = roles.client().clientIds();
    if (configured != null && !configured.isEmpty()) {
      return List.copyOf(configured);
    }
    return clientId == null || clientId.isBlank() ? List.of() : List.of(clientId);
  }

  static String stripTrailingSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  /**
   * @param enabled      register the RS256 {@code JwtDecoder} (and allow the default chain)
   * @param defaultChain register a stateless "everything authenticated" filter chain when the
   *                     app has none of its own
   * @param permitAll    paths the default chain leaves open
   */
  public record ResourceServer(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("true") boolean defaultChain,
      @DefaultValue({"/actuator/health", "/actuator/health/**", "/actuator/info", "/error"})
          List<String> permitAll) {}

  /**
   * @param enabled register the resolver beans and the {@code @JoinKey} argument resolver
   * @param claim   claim name; the wrapper's {@code sso-join-key} mapper always emits {@code join_key}
   * @param column  what the value IS for this app ({@code sso.client.join_column}): nid | phone |
   *                email. Unset on the client means email (spec §5.6).
   */
  public record JoinKey(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("join_key") String claim,
      @DefaultValue("email") String column) {}

  /** Authority mapping switches. */
  public record Roles(
      @DefaultValue InstitutionRoleMapping institution,
      @DefaultValue ClientRoleMapping client,
      @DefaultValue ScopeMapping scopes) {}

  /** {@code realm_access.roles} {@code inst:<slug>:<role>} → {@code INST_<slug>_<role>}. */
  public record InstitutionRoleMapping(
      @DefaultValue("true") boolean enabled, @DefaultValue("INST_") String prefix) {}

  /** {@code resource_access.<clientId>.roles} → {@code ROLE_<role>}. */
  public record ClientRoleMapping(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("ROLE_") String prefix,
      @DefaultValue List<String> clientIds) {}

  /** {@code scope} → {@code SCOPE_<scope>} (Spring's default). */
  public record ScopeMapping(@DefaultValue("true") boolean enabled) {}

  /** @param slug the app's own institution slug (e.g. {@code minagri}); used by {@code InstitutionRoles}. */
  public record Institution(String slug) {}
}
