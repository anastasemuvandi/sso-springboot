package rw.gov.risa.sso.starter.roles;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;

/** Keycloak claim shapes: {@code realm_access.roles}, {@code resource_access.<client>.roles}. */
public final class TokenClaims {

  private TokenClaims() {}

  public static List<String> realmRoles(Jwt jwt) {
    Object realmAccess = jwt.getClaims().get("realm_access");
    if (realmAccess instanceof Map<?, ?> m) {
      return strings(m.get("roles"));
    }
    return List.of();
  }

  public static List<String> clientRoles(Jwt jwt, String clientId) {
    Object resourceAccess = jwt.getClaims().get("resource_access");
    if (resourceAccess instanceof Map<?, ?> m && m.get(clientId) instanceof Map<?, ?> client) {
      return strings(client.get("roles"));
    }
    return List.of();
  }

  static List<String> strings(Object value) {
    if (value instanceof Collection<?> c) {
      List<String> out = new ArrayList<>(c.size());
      for (Object o : c) {
        if (o != null) {
          out.add(o.toString());
        }
      }
      return List.copyOf(out);
    }
    return List.of();
  }
}
