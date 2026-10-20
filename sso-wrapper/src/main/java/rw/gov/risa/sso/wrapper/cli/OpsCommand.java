package rw.gov.risa.sso.wrapper.cli;

import java.util.List;

/**
 * An operations command run as {@code java -jar sso-wrapper.jar <name> [args...]}: the app starts
 * without a web server, runs the command and exits with its code. Implement as a Spring bean;
 * names are kebab-case and unique (e.g. {@code delegated-check}, {@code fgap-plan-check}).
 */
public interface OpsCommand {

    String name();

    /** One line for {@code help}. */
    String description();

    /** @return the process exit code (0 = success; non-zero = findings or failure) */
    int run(List<String> args) throws Exception;
}
