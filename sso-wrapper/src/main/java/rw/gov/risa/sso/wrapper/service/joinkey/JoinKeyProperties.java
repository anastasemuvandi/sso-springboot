package rw.gov.risa.sso.wrapper.service.joinkey;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Join key (spec §5.6).
 *
 * @param enforce             JOIN_KEY_ENFORCE: master switch of the login-time gate
 * @param enforceUnsetClients JOIN_KEY_ENFORCE_UNSET_CLIENTS: also gate clients that recorded no column (email default)
 * @param validateFormat      JOIN_KEY_VALIDATE_FORMAT: check the value's shape, not only presence/uniqueness
 * @param readinessLimit      JOIN_KEY_READINESS_LIMIT: max assigned users a readiness scan examines
 */
@ConfigurationProperties(prefix = "sso.join-key")
public record JoinKeyProperties(
        @DefaultValue("true") boolean enforce,
        @DefaultValue("false") boolean enforceUnsetClients,
        @DefaultValue("true") boolean validateFormat,
        @DefaultValue("200") int readinessLimit) {
}
