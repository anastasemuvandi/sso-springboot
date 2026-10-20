package rw.gov.risa.sso.wrapper.service.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Platform administration (spec §4 platform admin, §5.16).
 *
 * @param enableSaml ENABLE_SAML: allow {@code POST /client/clients} with {@code protocol: saml}
 */
@ConfigurationProperties(prefix = "sso.platform")
public record PlatformProperties(@DefaultValue("false") boolean enableSaml) {
}
