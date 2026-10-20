package rw.gov.risa.sso.wrapper.service.ippis.cli;

import java.util.List;

import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.cli.OpsCommand;

/** {@code java -jar sso-wrapper.jar seed-identity-mirror ...} — see {@link IppisCommands}. */
@Component
public class SeedIdentityMirrorCommand implements OpsCommand {

    private final IppisCommands commands;

    public SeedIdentityMirrorCommand(IppisCommands commands) {
        this.commands = commands;
    }

    @Override
    public String name() {
        return "seed-identity-mirror";
    }

    @Override
    public String description() {
        return "Seed the DEV identity mirror with a roster covering every directory state";
    }

    @Override
    public int run(List<String> args) {
        return commands.run(name(), args);
    }
}
