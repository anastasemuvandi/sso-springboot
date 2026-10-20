package rw.gov.risa.sso.wrapper.service.lifecycle;

import java.util.List;

/**
 * Offboarding step 5 (spec §5.8, invariant 9): remove the person from every client's
 * {@code sso-access-users} named-grant policy. Implemented over the shared
 * {@code service.clientaccess.ClientAccessService.removeNamedGrantsEverywhere}.
 */
public interface LeaverAccessStripper {

    /** @return the clientIds whose {@code sso-access-users} entry for this user was removed */
    List<String> removeNamedGrantsEverywhere(String userId);
}
