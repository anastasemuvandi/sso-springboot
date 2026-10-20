package rw.gov.risa.sso.wrapper.service.ippis.cli;

import java.util.List;

import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.cli.OpsCommand;

/** {@code java -jar sso-wrapper.jar ippis-leavers ...} — see {@link IppisCommands}. */
@Component
public class IppisLeaversCommand implements OpsCommand {

    private final IppisCommands commands;

    public IppisLeaversCommand(IppisCommands commands) {
        this.commands = commands;
    }

    @Override
    public String name() {
        return "ippis-leavers";
    }

    @Override
    public String description() {
        return "Offboard IPPIS leavers only (no open post anywhere)";
    }

    @Override
    public int run(List<String> args) {
        return commands.run(name(), args);
    }
}
