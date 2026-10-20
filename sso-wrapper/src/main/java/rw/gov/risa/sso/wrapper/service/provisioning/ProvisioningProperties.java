package rw.gov.risa.sso.wrapper.service.provisioning;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Employee provisioning (spec §5.11).
 *
 * @param maxBatch    EMPLOYEE_PROVISION_MAX_BATCH: most employees one request may act on (400 above it)
 * @param devPassword EMPLOYEE_PROVISION_DEV_PASSWORD: a shared password for created accounts — honoured ONLY
 *                    when DEBUG is on ({@link EmployeeProvisioningService#devPassword()}); ignored, loudly, otherwise
 */
@ConfigurationProperties(prefix = "sso.provisioning")
public record ProvisioningProperties(
        @DefaultValue("100") int maxBatch,
        @DefaultValue("") String devPassword) {

    public ProvisioningProperties {
        maxBatch = Math.max(1, maxBatch);
        devPassword = devPassword == null ? "" : devPassword;
    }

    /** Never print the shared password. */
    @Override
    public String toString() {
        return "ProvisioningProperties[maxBatch=" + maxBatch + ", devPassword=" + (devPassword.isBlank() ? "unset"
                : "<set>") + "]";
    }
}
