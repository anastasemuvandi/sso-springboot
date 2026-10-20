package rw.gov.risa.sso.wrapper.security;

import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Authentication whose principal is a {@link CallerPrincipal}. Authorities are
 * {@code ROLE_<realmRole>} for every realm role (e.g. {@code ROLE_super_admin},
 * {@code ROLE_inst:minagri:admin}). Inject the caller with
 * {@code @AuthenticationPrincipal CallerPrincipal caller}.
 */
public class CallerAuthentication extends AbstractAuthenticationToken {

    private final CallerPrincipal caller;

    public CallerAuthentication(CallerPrincipal caller) {
        super(authorities(caller));
        this.caller = caller;
        setAuthenticated(true);
    }

    private static Collection<GrantedAuthority> authorities(CallerPrincipal caller) {
        return caller.realmRoles().stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
    }

    @Override
    public Object getCredentials() {
        return caller.accessToken();
    }

    @Override
    public CallerPrincipal getPrincipal() {
        return caller;
    }

    @Override
    public String getName() {
        return caller.username();
    }
}
