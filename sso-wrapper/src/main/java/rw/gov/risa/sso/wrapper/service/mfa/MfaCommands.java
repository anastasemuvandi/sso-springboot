package rw.gov.risa.sso.wrapper.service.mfa;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import rw.gov.risa.sso.wrapper.cli.OpsCommand;

/**
 * Ops commands: {@code mfa-install [--check] [--rebuild-flow] [--no-bind] [--default-actions]} and
 * {@code mfa-enrol [--dry-run]}.
 */
@Configuration
public class MfaCommands {

    @Bean
    OpsCommand mfaInstallCommand(MfaFlowInstaller installer) {
        return new OpsCommand() {
            @Override
            public String name() {
                return "mfa-install";
            }

            @Override
            public String description() {
                return "Apply the browser-totp flow + MFA realm settings via the Admin API "
                        + "[--check] [--rebuild-flow] [--no-bind] [--default-actions]";
            }

            @Override
            public int run(List<String> args) {
                boolean check = args.contains("--check");
                MfaFlowInstaller.Report r = installer.install(new MfaFlowInstaller.Options(!check,
                        args.contains("--rebuild-flow"), !args.contains("--no-bind"), args.contains("--default-actions")));
                r.findings().forEach(f -> System.out.println("  - " + f));
                if (r.blocked() != null) {
                    System.err.println(r.blocked());
                    return 2;
                }
                if (r.findings().isEmpty()) {
                    System.out.println("nothing to do: the realm matches the definition");
                    return 0;
                }
                return check ? 1 : 0;
            }
        };
    }

    @Bean
    OpsCommand mfaEnrolCommand(MfaBulkEnrolment bulk) {
        return new OpsCommand() {
            @Override
            public String name() {
                return "mfa-enrol";
            }

            @Override
            public String description() {
                return "Bulk-assign CONFIGURE_TOTP to users without OTP (idempotent, resumable) [--dry-run]";
            }

            @Override
            public int run(List<String> args) {
                MfaBulkEnrolment.Summary s = bulk.run(args.contains("--dry-run"));
                System.out.println(s.asMap());
                return s.errors.isEmpty() ? 0 : 1;
            }
        };
    }
}
