package rw.gov.risa.sso.ippismock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * IPPIS mock: simulates the authoritative IPPIS (GESB) HR/payroll system so the SSO's
 * IPPIS attribute-sourcing pipeline can be built and demoed before real IPPIS access exists.
 *
 * <p>Port of the reference {@code ippis-mock/app/main.py}. Two surfaces:
 * <ol>
 *   <li>the <b>read contract</b> the real IPPIS must satisfy ({@code /employees}, {@code /people});</li>
 *   <li>a <b>mock control plane</b> (this service only, never called by the SSO) that lets an operator
 *       enroll, update and terminate employees and so drive the inbound flow live.</li>
 * </ol>
 * Dev and tests only. Every person in the seed is invented.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IppisMockApplication {
    public static void main(String[] args) {
        SpringApplication.run(IppisMockApplication.class, args);
    }
}
