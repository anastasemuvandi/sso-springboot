package rw.gov.risa.sso.wrapper.service.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.config.SsoProperties;

/**
 * Says out loud what {@code EMPLOYEE_PROVISION_DEV_PASSWORD} is doing, in both directions (reference
 * {@code main.py}): honoured (DEBUG on) → WARN, every created account shares one known password; set but
 * ignored (DEBUG off) → ERROR, so nobody goes reading Keycloak logs wondering why it does not work. The
 * value itself is never logged.
 */
@Component
public class DevPasswordStartupCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DevPasswordStartupCheck.class);

    private final ProvisioningProperties props;
    private final SsoProperties sso;

    public DevPasswordStartupCheck(ProvisioningProperties props, SsoProperties sso) {
        this.props = props;
        this.sso = sso;
    }

    @Override
    public void afterPropertiesSet() {
        check();
    }

    /** {@code honoured}, {@code ignored} or {@code unset} (also what was logged). */
    public String check() {
        if (props.devPassword().isBlank()) {
            return "unset";
        }
        if (sso.debug()) {
            log.warn("employee provisioning: EMPLOYEE_PROVISION_DEV_PASSWORD is set — every account created from the "
                    + "employee list will share one known password and receive no set-password email. TEST STACKS ONLY.");
            return "honoured";
        }
        log.error("employee provisioning: EMPLOYEE_PROVISION_DEV_PASSWORD is set but DEBUG is false, so it is IGNORED — "
                + "created accounts keep the real flow (random password + set-password email). Unset it.");
        return "ignored";
    }
}
