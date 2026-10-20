package rw.gov.risa.sso.wrapper.security;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Verified {@link Jwt} → {@link CallerAuthentication}. A token without preferred_username is rejected (401). */
@Component
public class CallerTokenConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String INSTITUTION_CLAIM = "institution";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new CallerAuthentication(toPrincipal(jwt));
    }

    public static CallerPrincipal toPrincipal(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        if (username == null || username.isBlank()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token", "Token has no preferred_username", null));
        }
        String sid = jwt.getClaimAsString("sid");
        if (sid == null) {
            sid = jwt.getClaimAsString("session_state");
        }
        return new CallerPrincipal(
                jwt.getSubject(),
                username,
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("azp"),
                realmRoles(jwt.getClaims()),
                clientRoles(jwt.getClaims()),
                institution(jwt.getClaims().get(INSTITUTION_CLAIM)),
                sid,
                jwt.getTokenValue(),
                Map.copyOf(jwt.getClaims()));
    }

    static Set<String> realmRoles(Map<String, Object> claims) {
        if (claims.get("realm_access") instanceof Map<?, ?> ra && ra.get("roles") instanceof Collection<?> roles) {
            Set<String> out = new LinkedHashSet<>();
            roles.forEach(r -> out.add(String.valueOf(r)));
            return Set.copyOf(out);
        }
        return Set.of();
    }

    static Map<String, Set<String>> clientRoles(Map<String, Object> claims) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (claims.get("resource_access") instanceof Map<?, ?> ra) {
            ra.forEach((client, access) -> {
                if (access instanceof Map<?, ?> a && a.get("roles") instanceof Collection<?> roles) {
                    Set<String> rs = new LinkedHashSet<>();
                    roles.forEach(r -> rs.add(String.valueOf(r)));
                    if (!rs.isEmpty()) {
                        out.put(String.valueOf(client), Set.copyOf(rs));
                    }
                }
            });
        }
        return Map.copyOf(out);
    }

    /** Attribute mappers emit a string or a single-element list; normalise to a lowercase slug. */
    static String institution(Object raw) {
        if (raw instanceof Collection<?> c) {
            raw = c.isEmpty() ? null : c.iterator().next();
        }
        if (!(raw instanceof String s)) {
            return null;
        }
        String slug = s.strip().toLowerCase();
        return slug.isEmpty() ? null : slug;
    }
}
