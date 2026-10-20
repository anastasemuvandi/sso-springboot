package rw.gov.risa.sso.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** GoRSSO audit service: append-only, SHA-256 hash-chained audit log. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
