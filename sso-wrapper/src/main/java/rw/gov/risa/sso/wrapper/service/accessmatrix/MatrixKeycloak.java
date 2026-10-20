package rw.gov.risa.sso.wrapper.service.accessmatrix;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.policy.accessmatrix.Holder;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.AdmitManyResult;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.RevokeResult;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.RevokeTarget;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService.VisibleClient;
import rw.gov.risa.sso.wrapper.service.institution.DelegatedExecution;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;

/**
 * The Keycloak reads and writes of the access matrix (reference {@code list_apps}, {@code _members_by_id},
 * {@code _client_holders}, {@code admit_users}/{@code revoke_users}), with the credential the delegated
 * surface uses ({@link DelegatedExecution}): member and own-group reads as the CALLER (FGAP v2 decides
 * too), a cross-cutting client's {@code sso-access-*} policies and other institutions' groups on the
 * service account — always AFTER the wrapper's own scope check, and everything filtered through this
 * institution's members before it leaves.
 */
@Component
public class MatrixKeycloak {

    /** Members read per group (a ministry is a few thousand people). */
    static final int MAX_MEMBERS_PER_GROUP = 100_000;

    private final ClientScopeService scopes;
    private final ClientAccessService access;
    private final DelegatedExecution delegated;
    private final KeycloakDirectory directory;

    public MatrixKeycloak(ClientScopeService scopes, ClientAccessService access, DelegatedExecution delegated,
                          KeycloakDirectory directory) {
        this.scopes = scopes;
        this.access = access;
        this.delegated = delegated;
        this.directory = directory;
    }

    /** Clients the institution may see: its own plus cross-cutting ones naming it (catalogue on the SA). */
    public List<VisibleClient> visibleClients(InstitutionContext ctx) {
        return scopes.clientsVisibleTo(ctx);
    }

    /** The institution group and (when the grant includes subgroups) every descendant. */
    public List<String> memberGroupIds(InstitutionContext ctx, CallerPrincipal caller) {
        return delegated.forCaller(caller).call(realm -> {
            Optional<GroupRepresentation> root = directory.groupByPath(realm, ctx.groupPath());
            if (root.isEmpty()) {
                return List.<String>of();
            }
            return ctx.grant().includeSubgroups() ? directory.groupTreeIds(realm, root.get().getId())
                    : List.of(root.get().getId());
        });
    }

    /** {@code {userId: member}} for the caller's institution (one member walk, as the caller). */
    public Map<String, UserRepresentation> members(InstitutionContext ctx, CallerPrincipal caller) {
        List<String> groups = memberGroupIds(ctx, caller);
        return delegated.forCaller(caller).call(realm -> {
            Map<String, UserRepresentation> out = new LinkedHashMap<>();
            for (String gid : groups) {
                for (UserRepresentation u : directory.groupMembers(realm, gid, MAX_MEMBERS_PER_GROUP)) {
                    if (u.getId() != null) {
                        out.putIfAbsent(u.getId(), u);
                    }
                }
            }
            return out;
        });
    }

    /**
     * Who of this institution currently reaches {@code clientId}, and how: named on {@code sso-access-users},
     * and/or a direct member of an admitted group — intersected with {@code members}, so other institutions'
     * staff on a cross-cutting client are never seen. {@code denied} = on the deny-list (not a holder).
     */
    public Map<String, Holder> holders(InstitutionContext ctx, CallerPrincipal caller, String clientId,
                                       boolean crossCutting, Map<String, UserRepresentation> members) {
        KeycloakExec policies = delegated.forClientPolicies(caller, crossCutting);
        ClientAssignment a = access.assignment(policies, clientId);
        Set<String> denied = new HashSet<>(a.deniedUserIds());
        Map<String, Acc> acc = new LinkedHashMap<>();
        for (String uid : a.userIds()) {
            if (members.containsKey(uid)) {
                acc.computeIfAbsent(uid, k -> new Acc()).direct = true;
            }
        }
        if (!a.groupIds().isEmpty()) {
            Set<String> own = new HashSet<>(memberGroupIds(ctx, caller));
            for (String gid : a.groupIds()) {
                KeycloakExec exec = own.contains(gid) ? delegated.forCaller(caller) : delegated.serviceAccount();
                List<UserRepresentation> people = exec.call(realm -> directory.groupMembers(realm, gid,
                        MAX_MEMBERS_PER_GROUP));
                String name = delegated.serviceAccount().call(realm -> directory.getGroup(realm, gid))
                        .map(GroupRepresentation::getName).orElse(gid);
                for (UserRepresentation u : people) {
                    if (u.getId() == null || !members.containsKey(u.getId())) {
                        continue;
                    }
                    Acc x = acc.computeIfAbsent(u.getId(), k -> new Acc());
                    x.groupIds.add(gid);
                    x.groupNames.add(name);
                }
            }
        }
        Map<String, Holder> out = new LinkedHashMap<>();
        acc.forEach((uid, x) -> out.put(uid, new Holder(x.direct, x.groupIds, x.groupNames, denied.contains(uid))));
        return out;
    }

    /** Name many people on the client in one policy write (each also has their denial lifted). */
    public AdmitManyResult admit(CallerPrincipal caller, String clientId, boolean crossCutting,
                                 Collection<String> userIds) {
        return access.admitUsers(delegated.forClientPolicies(caller, crossCutting), clientId, userIds);
    }

    /** Take the client away from many people in one pass (un-name and/or deny when held by group). */
    public RevokeResult revoke(CallerPrincipal caller, String clientId, boolean crossCutting,
                               List<RevokeTarget> targets) {
        return access.revokeUsers(delegated.forClientPolicies(caller, crossCutting), clientId, targets);
    }

    private static final class Acc {
        boolean direct;
        final List<String> groupIds = new ArrayList<>();
        final List<String> groupNames = new ArrayList<>();
    }
}
