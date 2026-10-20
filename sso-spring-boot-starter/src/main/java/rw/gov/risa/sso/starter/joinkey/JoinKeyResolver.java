package rw.gov.risa.sso.starter.joinkey;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Reads the {@code join_key} claim — the value the wrapper's single {@code sso-join-key} protocol
 * mapper emits whatever the client's column is (nid | phone | email). The claim never says which
 * column it came from; the app knows ({@code gorsso.join-key.column}).
 *
 * <p>Declare your own bean of this type to change the behaviour (e.g. a fallback lookup).
 */
public class JoinKeyResolver {

  private final String claim;
  private final JoinKeyColumn column;

  public JoinKeyResolver(String claim, JoinKeyColumn column) {
    this.claim = claim;
    this.column = column;
  }

  public String claim() {
    return claim;
  }

  public JoinKeyColumn column() {
    return column;
  }

  /** The normalised join key, if the token carries a non-blank one. */
  public Optional<String> resolve(Jwt jwt) {
    if (jwt == null) {
      return Optional.empty();
    }
    Object raw = jwt.getClaims().get(claim);
    if (raw == null) {
      return Optional.empty();
    }
    String v = column.normalise(raw.toString());
    return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v);
  }

  public Optional<JoinKeyValue> resolveValue(Jwt jwt) {
    return resolve(jwt).map(v -> new JoinKeyValue(column, v));
  }

  public Optional<String> resolve(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken t) {
      return resolve(t.getToken());
    }
    if (authentication != null && authentication.getPrincipal() instanceof Jwt j) {
      return resolve(j);
    }
    return Optional.empty();
  }

  /** For the current request's caller. */
  public Optional<String> current() {
    return resolve(SecurityContextHolder.getContext().getAuthentication());
  }

  /** For the current request's caller, or {@link JoinKeyMissingException}. */
  public String require() {
    return current().orElseThrow(JoinKeyMissingException::new);
  }
}
