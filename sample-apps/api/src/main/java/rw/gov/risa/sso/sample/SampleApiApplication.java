package rw.gov.risa.sso.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * One parameterised demo API; run it with profile {@code minagri} or {@code minisante}.
 * Security comes entirely from {@code sso-spring-boot-starter}: the default stateless chain,
 * the RS256 decoder (iss + azp), the {@code inst:<slug>:*} / client-role authorities and the
 * {@code @JoinKey} argument.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableMethodSecurity
public class SampleApiApplication {
  public static void main(String[] args) {
    SpringApplication.run(SampleApiApplication.class, args);
  }
}
