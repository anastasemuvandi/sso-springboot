package rw.gov.risa.sso.wrapper.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import rw.gov.risa.sso.wrapper.security.CallerAuthentication;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;

/**
 * MockMvc callers for controller tests: {@code mvc.perform(get("/x").with(TestCallers.superAdmin()))}.
 * Builds the same {@link CallerPrincipal} the JWT converter would.
 */
public final class TestCallers {
    private TestCallers() {}

    public static RequestPostProcessor superAdmin() {
        return as(caller("platform.super", Set.of("super_admin"), null));
    }

    public static RequestPostProcessor admin() {
        return as(caller("platform.admin", Set.of("admin"), null));
    }

    public static RequestPostProcessor systemAdmin() {
        return as(caller("platform.system", Set.of("system_admin"), null));
    }

    /** A delegated institution admin: realm role {@code inst:<slug>:<tierRole>}. */
    public static RequestPostProcessor institutionAdmin(String slug, String tierRole) {
        return as(caller(slug + ".admin", Set.of("inst:" + slug + ":" + tierRole), slug));
    }

    public static RequestPostProcessor plainUser() {
        return as(caller("citizen.user", Set.of("default-roles-government-internal"), null));
    }

    public static RequestPostProcessor as(CallerPrincipal caller) {
        return authentication(new CallerAuthentication(caller));
    }

    public static CallerPrincipal caller(String username, Set<String> realmRoles, String institutionClaim) {
        return caller(username, realmRoles, Map.of(), institutionClaim);
    }

    public static CallerPrincipal caller(String username, Set<String> realmRoles,
            Map<String, Set<String>> clientRoles, String institutionClaim) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("preferred_username", username);
        claims.put("sub", "uid-" + username);
        claims.put("azp", "sso-admin-ui");
        return new CallerPrincipal("uid-" + username, username, username + "@gov.rw", username, "sso-admin-ui",
                new LinkedHashSet<>(realmRoles), clientRoles, institutionClaim, "sid-" + username,
                "test-token-" + username, claims);
    }
}
