package rw.gov.risa.sso.wrapper.cli;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.ApplicationContext;

/** Finds and runs the {@link OpsCommand} named by the first program argument. */
public final class OpsCommandRunner {
    private OpsCommandRunner() {}

    /** True when {@code args} asks for an ops command rather than the server. */
    public static boolean isCommand(String[] args) {
        return args.length > 0 && !args[0].startsWith("-");
    }

    public static ExitCodeGenerator run(ApplicationContext ctx, String[] args) {
        String name = args[0];
        List<String> rest = Arrays.asList(args).subList(1, args.length);
        List<OpsCommand> commands = ctx.getBeansOfType(OpsCommand.class).values().stream()
                .sorted(Comparator.comparing(OpsCommand::name)).toList();
        if ("help".equals(name)) {
            commands.forEach(c -> System.out.printf("  %-24s %s%n", c.name(), c.description()));
            return () -> 0;
        }
        OpsCommand command = commands.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
        if (command == null) {
            System.err.println("unknown command '" + name + "'. Available: "
                    + commands.stream().map(OpsCommand::name).toList());
            return () -> 2;
        }
        try {
            int code = command.run(rest);
            return () -> code;
        } catch (Exception e) {
            System.err.println(name + " failed: " + e.getMessage());
            return () -> 1;
        }
    }
}
