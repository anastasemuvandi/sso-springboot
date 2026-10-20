package rw.gov.risa.sso.bff.session;

import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.nimbusds.jwt.JWTParser;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * Builds {@link SessionInfo} from the caller's access token (decoded, NOT verified: the BFF trusts it
 * for nothing), falling back to the id_token claims for an opaque access token.
 *
 * <p>Platform tier = realm role {@code super_admin | admin | system_admin}. Institution admin = any
 * realm role {@code inst:<slug>:<role>} (the reference console's routing rule); the slug is
 * lower-cased. Landing: platform admins {@code /dashboard}, institution admins {@code /institution},
 * anyone else {@code /no-access}.
 */
@Service
public class SessionInfoService {
    public static final Set<String> PLATFORM_ROLES = Set.of("super_admin", "admin", "system_admin");

    public SessionInfo describe(OAuth2AuthenticationToken authentication, OAuth2AuthorizedClient client) {
        Map<String, Object> claims = Map.of();
        String expiresAt = null;
        if (client != null && client.getAccessToken() != null) {
            claims = decode(client.getAccessToken().getTokenValue());
            Instant exp = client.getAccessToken().getExpiresAt();
            expiresAt = exp == null ? null : exp.toString();
        }
        Map<String, Object> idClaims = authentication.getPrincipal() instanceof OidcUser u ? u.getClaims() : Map.of();
        if (claims.isEmpty()) {
            claims = idClaims;
        }

        Set<String> roles = new TreeSet<>(realmRoles(claims));
        List<String> platform = roles.stream().filter(PLATFORM_ROLES::contains).toList();
        Set<String> slugs = new TreeSet<>();
        for (String role : roles) {
            if (role.startsWith("inst:")) {
                String[] parts = role.split(":", 3);
                if (parts.length == 3 && !parts[1].isBlank()) {
                    slugs.add(parts[1].toLowerCase(Locale.ROOT));
                }
            }
        }
        boolean platformAdmin = !platform.isEmpty();
        boolean institutionAdmin = !slugs.isEmpty();
        String landing = platformAdmin ? "/dashboard" : institutionAdmin ? "/institution" : "/no-access";

        String username = first(claims, idClaims, "preferred_username");
        String name = first(claims, idClaims, "name");
        if (name == null) {
            String given = first(claims, idClaims, "given_name");
            String family = first(claims, idClaims, "family_name");
            String joined = ((given == null ? "" : given) + " " + (family == null ? "" : family)).strip();
            name = joined.isEmpty() ? username : joined;
        }
        return new SessionInfo(true, username, name, first(claims, idClaims, "email"), List.copyOf(roles), platform,
                platformAdmin, institutionAdmin, slugs.isEmpty() ? null : slugs.iterator().next(),
                List.copyOf(slugs), landing, expiresAt);
    }

    static Map<String, Object> decode(String token) {
        try {
            return JWTParser.parse(token).getJWTClaimsSet().getClaims();
        } catch (ParseException | RuntimeException e) {
            return Map.of(); // opaque token
        }
    }

    @SuppressWarnings("unchecked")
    static List<String> realmRoles(Map<String, Object> claims) {
        List<String> out = new ArrayList<>();
        if (claims.get("realm_access") instanceof Map<?, ?> access && access.get("roles") instanceof Collection<?> c) {
            c.forEach(r -> out.add(String.valueOf(r)));
        }
        if (claims.get("realm_roles") instanceof Collection<?> c) {
            c.forEach(r -> out.add(String.valueOf(r)));
        }
        return out;
    }

    private static String first(Map<String, Object> a, Map<String, Object> b, String key) {
        Object v = a.get(key);
        if (!(v instanceof String) || ((String) v).isBlank()) {
            v = b.get(key);
        }
        return v instanceof String s && !s.isBlank() ? s : null;
    }
}
