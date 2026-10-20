package rw.gov.risa.sso.wrapper.service.clientaccess;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-client login access (spec §5.4). The exempt list is core: {@code sso.keycloak.exempt-clients}
 * ({@code ACCESS_ENFORCEMENT_EXEMPT_CLIENTS}).
 *
 * @param defaultDeny ACCESS_DEFAULT_DENY: a client with no user/group policy rejects every login
 */
@ConfigurationProperties(prefix = "sso.access")
public record ClientAccessProperties(@DefaultValue("true") boolean defaultDeny) {
}
