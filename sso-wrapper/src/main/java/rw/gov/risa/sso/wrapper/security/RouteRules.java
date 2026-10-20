package rw.gov.risa.sso.wrapper.security;

import java.util.List;

/**
 * Which routes sit behind which gate. Kept as data so tests can assert every declared
 * controller route falls into exactly one bucket (spec §5.16: every declared route reachable).
 */
public final class RouteRules {
    private RouteRules() {}

    /** Platform-admin routes: realm role super_admin | admin | system_admin required. */
    public static final List<String> ADMIN_TIER = List.of(
            "/client/**", "/users/**", "/users", "/agency/**", "/roles/**", "/authorization/**", "/realm/**",
            "/dashboard/**", "/delegated-permissions/**", "/delegated-permissions", "/institutions/**",
            "/institutions", "/audit/**", "/offboarding/**", "/access/**", "/lifecycle/**", "/admin/**");

    /** Authenticated, but resolve their own scope (deliberately NOT behind the admin tier). */
    public static final List<String> SELF_SCOPED = List.of(
            "/institution-admin/**", "/employees/**", "/employees");

    /** Need a valid bearer but no particular role. /auth/verify fails closed. */
    public static final List<String> AUTHENTICATED = List.of("/auth/verify", "/auth/userinfo");

    /** Public: protocol surface, account setup, signed webhook, health, docs. */
    public static final List<String> PUBLIC = List.of(
            "/auth/login", "/auth/logout", "/oauth2/**", "/saml/**", "/health/**", "/health",
            "/account/**", "/ippis/events", "/actuator/health/**", "/actuator/health", "/actuator/info",
            "/v3/api-docs/**", "/v3/api-docs", "/swagger-ui/**", "/swagger-ui.html", "/docs/**", "/docs",
            "/error");

    /**
     * Public paths on which an Authorization header is NOT a bearer for us: the token endpoint
     * gets HTTP Basic client credentials, and a stale bearer from a browser must not 401 the
     * account-setup pages.
     */
    public static final List<String> IGNORE_BEARER = List.of(
            "/oauth2/**", "/account/**", "/ippis/events", "/health/**", "/saml/**", "/auth/login");
}
