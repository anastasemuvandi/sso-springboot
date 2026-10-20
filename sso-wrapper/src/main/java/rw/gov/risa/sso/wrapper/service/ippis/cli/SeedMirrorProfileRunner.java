package rw.gov.risa.sso.wrapper.service.ippis.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.service.ippis.DevMirrorSeeder;

/**
 * {@code SPRING_PROFILES_ACTIVE=seed-mirror}: seed the dev mirror once at startup and keep serving
 * (the dev compose stack uses it). The seeder itself refuses staging/prod.
 */
@Component
@Profile("seed-mirror")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "sso.ops-mode", havingValue = "false",
        matchIfMissing = true)
public class SeedMirrorProfileRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedMirrorProfileRunner.class);

    private final DevMirrorSeeder seeder;

    public SeedMirrorProfileRunner(DevMirrorSeeder seeder) {
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = seeder.seed(false, System.out);
        log.info("seed-mirror profile: dev mirror seeding finished with code {}", code);
    }
}
