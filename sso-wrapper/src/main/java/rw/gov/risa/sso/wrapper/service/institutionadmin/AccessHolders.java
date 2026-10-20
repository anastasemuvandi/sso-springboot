package rw.gov.risa.sso.wrapper.service.institutionadmin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.policy.accessmatrix.Holder;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.institution.DelegatedExecution;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;

/**
 * Who, of ONE institution, currently reaches a client — and how (port of {@code _client_holders}).
 * Named grants and group grants are unioned; the group side is intersected with the institution's
 * members, which is what keeps a CROSS-CUTTING client honest (other institutions' staff hold it through
 * their own groups and are never returned). {@code denied} = on {@code sso-access-denied-users}: such a
 * person is not a holder (the token gate refuses them).
 *
 * <p>Credential: a cross-cutting client's {@code sso-access-*} policies and any group that is not this
 * institution's are the platform's objects, read with the service account (FGAP v2 cannot describe
 * them) — only after the caller's scope check, and filtered through {@code members} before anything
 * leaves. An own client and own groups are read as the caller.
 */
@Component
public class AccessHolders {

    private final ClientAccessService access;
    private final InstitutionKeycloak kc;
    private final DelegatedExecution delegated;

    public AccessHolders(ClientAccessService access, InstitutionKeycloak kc, DelegatedExecution delegated) {
        this.access = access;
        this.kc = kc;
        this.delegated = delegated;
    }

    /** The executor for one client's {@code sso-access-*} policies. */
    public KeycloakExec policiesExec(KeycloakExec callerExec, boolean crossCutting) {
        return access.execFor(callerExec, crossCutting);
    }

    /**
     * {@code user id -> holder} for the institution's members on {@code clientId}.
     *
     * @param ownGroupIds the institution's member group ids (resolved lazily: most clients have no group)
     */
    public Map<String, Holder> holders(KeycloakExec callerExec, String clientId, Map<String, UserRepresentation> members,
                                       boolean crossCutting, Supplier<List<String>> ownGroupIds) {
        ClientAssignment assignment = access.assignment(policiesExec(callerExec, crossCutting), clientId);
        Set<String> denied = Set.copyOf(assignment.deniedUserIds());
        Map<String, Builder> out = new LinkedHashMap<>();
        for (String uid : assignment.userIds()) {
            if (uid != null && members.containsKey(uid)) {
                out.computeIfAbsent(uid, k -> new Builder(denied.contains(k))).direct = true;
            }
        }
        Set<String> own = null;
        for (String gid : assignment.groupIds()) {
            if (gid == null) {
                continue;
            }
            if (own == null) {
                own = Set.copyOf(ownGroupIds.get());
            }
            KeycloakExec groupExec = !callerExec.asCaller() || own.contains(gid) ? callerExec
                    : delegated.serviceAccount();
            String name = null;
            for (UserRepresentation m : kc.groupMembers(groupExec, gid)) {
                String uid = m.getId();
                if (uid == null || !members.containsKey(uid)) {
                    continue;
                }
                if (name == null) {
                    name = kc.groupName(groupExec, gid);
                    if (name == null) {
                        name = gid;
                    }
                }
                Builder b = out.computeIfAbsent(uid, k -> new Builder(denied.contains(k)));
                b.groupIds.add(gid);
                b.groupNames.add(name);
            }
        }
        Map<String, Holder> result = new LinkedHashMap<>();
        out.forEach((uid, b) -> result.put(uid, new Holder(b.direct, b.groupIds, b.groupNames, b.denied)));
        return result;
    }

    private static final class Builder {
        boolean direct;
        final boolean denied;
        final List<String> groupIds = new ArrayList<>();
        final List<String> groupNames = new ArrayList<>();

        Builder(boolean denied) {
            this.denied = denied;
        }
    }
}
