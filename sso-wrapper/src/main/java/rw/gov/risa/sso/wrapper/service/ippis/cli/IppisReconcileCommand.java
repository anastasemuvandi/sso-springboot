package rw.gov.risa.sso.wrapper.service.ippis.cli;

import java.util.List;

import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.cli.OpsCommand;

/** {@code java -jar sso-wrapper.jar ippis-reconcile ...} — see {@link IppisCommands}. */
@Component
public class IppisReconcileCommand implements OpsCommand {

    private final IppisCommands commands;

    public IppisReconcileCommand(IppisCommands commands) {
        this.commands = commands;
    }

    @Override
    public String name() {
        return "ippis-reconcile";
    }

    @Override
    public String description() {
        return "Unified IPPIS reconcile (delta, or --full): sync attributes and offboard leavers";
    }

    @Override
    public int run(List<String> args) {
        return commands.run(name(), args);
    }
}
