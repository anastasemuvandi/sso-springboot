package rw.gov.risa.sso.ippismock.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ippis-mock")
public record IppisMockProperties(
        @DefaultValue("change-me-in-ippis") String apiKey,
        String dataFile,
        @DefaultValue Webhook webhook) {

    public record Webhook(
            String url,
            String secret,
            @DefaultValue("5s") Duration timeout,
            @DefaultValue("timestamped") String signatureMode) {

        public boolean enabled() {
            return url != null && !url.isBlank() && secret != null && !secret.isBlank();
        }
    }
}
