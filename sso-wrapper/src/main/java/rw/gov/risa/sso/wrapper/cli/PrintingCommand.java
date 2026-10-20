package rw.gov.risa.sso.wrapper.cli;

import java.io.PrintStream;
import java.util.List;
import java.util.Set;

/**
 * Base for the platform ops commands: output goes to a swappable {@link PrintStream} (tests), unknown
 * options are a usage error (64), and an unexpected failure is exit code 1 with the reason printed.
 */
public abstract class PrintingCommand implements OpsCommand {

    public static final int USAGE = 64;

    protected PrintStream out = System.out;

    /** Test seam. */
    public PrintingCommand printTo(PrintStream stream) {
        this.out = stream;
        return this;
    }

    protected abstract Set<String> flags();

    protected abstract Set<String> options();

    protected abstract int execute(CliArgs args) throws Exception;

    @Override
    public final int run(List<String> raw) {
        CliArgs args = CliArgs.parse(raw, flags(), options());
        if (!args.unknown().isEmpty()) {
            out.println("ERROR: unknown option(s) " + args.unknown() + " for " + name());
            out.println("usage: " + name() + " — " + description());
            return USAGE;
        }
        try {
            return execute(args);
        } catch (Exception e) {
            out.println("ERROR: " + name() + " failed: " + e.getMessage());
            return 1;
        }
    }
}
