package rw.gov.risa.sso.wrapper.policy.clientaccess;

import java.util.List;

/**
 * The answer for one (person, client), with how it was reached. {@code direct} and
 * {@code viaGroupIds} describe how the person is held independently of the outcome — they can
 * be set on someone who is nonetheless denied.
 */
public record AccessDecision(
        boolean hasAccess,
        AccessReason reason,
        boolean direct,
        List<String> viaGroupIds,
        boolean denied,
        boolean deniedByName,
        List<String> deniedGroupIds) {

    public AccessDecision {
        viaGroupIds = List.copyOf(viaGroupIds);
        deniedGroupIds = List.copyOf(deniedGroupIds);
    }

    public AccessState state() {
        if (reason == AccessReason.EXEMPT) {
            return AccessState.EXEMPT;
        }
        if (denied) {
            return AccessState.REVOKED;
        }
        if (hasAccess) {
            return direct || !viaGroupIds.isEmpty() ? AccessState.GRANTED : AccessState.OPEN;
        }
        return AccessState.NOT_GRANTED;
    }
}
