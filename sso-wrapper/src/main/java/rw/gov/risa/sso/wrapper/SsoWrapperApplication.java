package rw.gov.risa.sso.wrapper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import rw.gov.risa.sso.wrapper.cli.OpsCommandRunner;

/**
 * {@code java -jar sso-wrapper.jar} runs the API server; {@code java -jar sso-wrapper.jar <command> ...}
 * runs an ops command (see {@code help}) without a web server or schedulers, then exits.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SsoWrapperApplication {

    public static void main(String[] args) {
        if (OpsCommandRunner.isCommand(args)) {
            SpringApplication app = new SpringApplication(SsoWrapperApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setDefaultProperties(java.util.Map.of(
                    "spring.task.scheduling.enabled", "false",
                    "sso.ops-mode", "true",
                    "RECONCILE_SERVICE_ACCOUNT_ROLES", "false"));
            ConfigurableApplicationContext ctx = app.run();
            System.exit(SpringApplication.exit(ctx, OpsCommandRunner.run(ctx, args)));
        }
        SpringApplication.run(SsoWrapperApplication.class, args);
    }
}
