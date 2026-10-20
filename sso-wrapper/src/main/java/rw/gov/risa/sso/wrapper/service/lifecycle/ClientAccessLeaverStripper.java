package rw.gov.risa.sso.wrapper.service.lifecycle;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews.GrantRemoval;

/**
 * {@link LeaverAccessStripper} over the shared {@link ClientAccessService}. A client whose policy
 * could not be rewritten is logged (and stays listed in the service's own failure map); the
 * offboarding is NOT rolled back for it — the account is already disabled and sessionless, so a
 * left-over named grant cannot be used, and re-enabling a leaver to "undo" would be worse.
 */
@Component
public class ClientAccessLeaverStripper implements LeaverAccessStripper {

    private static final Logger log = LoggerFactory.getLogger(ClientAccessLeaverStripper.class);

    private final ClientAccessService clientAccess;

    public ClientAccessLeaverStripper(ClientAccessService clientAccess) {
        this.clientAccess = clientAccess;
    }

    @Override
    public List<String> removeNamedGrantsEverywhere(String userId) {
        GrantRemoval removal = clientAccess.removeNamedGrantsEverywhere(userId);
        if (removal.failures() != null && !removal.failures().isEmpty()) {
            log.warn("offboarding {}: sso-access-users could not be rewritten on {} — fix by hand or re-run",
                    userId, removal.failures().keySet());
        }
        return List.copyOf(removal.changedClientIds());
    }
}
