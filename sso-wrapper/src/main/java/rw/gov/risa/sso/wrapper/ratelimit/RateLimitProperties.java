package rw.gov.risa.sso.wrapper.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled       RATE_LIMIT_ENABLED
 * @param defaultLimit  RATE_LIMIT_DEFAULT, applied per IP to every route
 */
@ConfigurationProperties(prefix = "sso.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1000/hour") String defaultLimit) {}
