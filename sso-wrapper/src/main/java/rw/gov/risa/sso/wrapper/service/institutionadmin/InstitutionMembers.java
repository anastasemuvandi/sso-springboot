package rw.gov.risa.sso.wrapper.service.institutionadmin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.WebApplicationException;

import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.error.KeycloakErrors;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionContextService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;

/**
 * The member half of the MANDATORY institution filter (port of {@code _group_id},
 * {@code _member_group_ids}, {@code _assert_member}, {@code _members_by_id}, {@code _assert_all_members}).
 * The group comes from the template ({@code members.group}), never from a request parameter; with
 * {@code include_subgroups} the departments count, walked breadth-first at any depth, so the read path
 * (listing) and the write path (per-id check) agree about who is a member.
 */
@Component
public class InstitutionMembers {

    private static final Logger log = LoggerFactory.getLogger(InstitutionMembers.class);

    /** 403 code: the target user is not a member of the caller's institution. */
    public static final String NOT_MEMBER = "not_institution_member";

    private final InstitutionKeycloak kc;

    public InstitutionMembers(InstitutionKeycloak kc) {
        this.kc = kc;
    }

    /** The institution's member group id; 404 when the institution was never onboarded. */
    public String groupId(InstitutionContext ctx, KeycloakExec exec) {
        String path = ctx.groupPath();
        return kc.groupByPath(exec, path)
                .map(GroupRepresentation::getId)
                .filter(id -> id != null && !id.isBlank())
                .orElseThrow(() -> ApiException.notFound("Institution group " + Py.repr(path) + " not found."));
    }

    /**
     * Every group whose members are in scope (the root, plus departments when the template says so).
     *
     * <p><b>Departments Keycloak does not (yet) let the caller see.</b> FGAP v2 on Keycloak 26.2 does not
     * propagate a group permission to children, so the plan ENUMERATES the departments that existed when it
     * was applied. A department created later (platform console, IPPIS) is not covered until the plan is
     * re-applied ({@code fgap-apply} / Terraform). Executing as the caller, Keycloak marks such a child
     * {@code access.view=false} and answers 403 to any read of it. Such a department is SKIPPED — logged at
     * WARN with the remedy — instead of failing the whole institution's listing: the wrapper's filter still
     * holds, Keycloak's half stays closed for the uncovered department, and the rest of the institution keeps
     * working. With the service account (flag off) every department is walked.
     */
    public List<String> memberGroupIds(InstitutionContext ctx, KeycloakExec exec) {
        String root = groupId(ctx, exec);
        if (!ctx.grant().includeSubgroups()) {
            return List.of(root);
        }
        List<String> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>();
        ids.add(root);
        seen.add(root);
        queue.add(root);
        while (!queue.isEmpty()) {
            String parent = queue.remove(0);
            List<GroupRepresentation> children;
            try {
                children = kc.groupChildren(exec, parent);
            } catch (WebApplicationException e) {
                if (!exec.asCaller() || parent.equals(root) || KeycloakErrors.status(e) != 403) {
                    throw e;
                }
                ids.remove(parent);
                uncovered(ctx, parent);
                continue;
            }
            for (GroupRepresentation child : children) {
                String id = child.getId();
                if (id == null || !seen.add(id)) {
                    continue;
                }
                if (exec.asCaller() && deniedView(child)) {
                    uncovered(ctx, child.getPath() != null ? child.getPath() : id);
                    continue;
                }
                ids.add(id);
                queue.add(id);
            }
        }
        return ids;
    }

    /** Keycloak's own verdict on the child, as returned with it ({@code access.view}); absent = not denied. */
    private static boolean deniedView(GroupRepresentation g) {
        Map<String, Boolean> access = g.getAccess();
        return access != null && Boolean.FALSE.equals(access.get("view"));
    }

    private static void uncovered(InstitutionContext ctx, String department) {
        log.warn("institution {}: department {} is not covered by the applied FGAP v2 plan, so Keycloak refuses "
                + "the delegated admin's own token on it; its members are left out until the plan is re-applied "
                + "(fgap-apply / Terraform)", ctx.institution(), department);
    }

    /**
     * {@code user id -> account} for the institution, de-duplicated (a person in the group and one of
     * its departments is one member). Requires {@code view-members}.
     */
    public Map<String, UserRepresentation> membersById(InstitutionContext ctx, KeycloakExec exec) {
        InstitutionContextService.requireCapability(ctx, Capability.VIEW_MEMBERS);
        Map<String, UserRepresentation> out = new LinkedHashMap<>();
        for (String gid : memberGroupIds(ctx, exec)) {
            for (UserRepresentation m : kc.groupMembers(exec, gid)) {
                if (m.getId() != null) {
                    out.putIfAbsent(m.getId(), m);
                }
            }
        }
        return out;
    }

    /** 403 unless {@code userId} belongs to the caller's institution (per object, before any read of it). */
    public void assertMember(InstitutionContext ctx, KeycloakExec exec, String userId) {
        Set<String> paths = new LinkedHashSet<>();
        for (GroupRepresentation g : kc.userGroups(exec, userId)) {
            if (g.getPath() != null) {
                paths.add(g.getPath());
            }
            if (g.getName() != null && !g.getName().isEmpty()) {
                paths.add("/" + g.getName());
            }
        }
        if (paths.stream().noneMatch(p -> ctx.grant().coversGroupPath(p))) {
            throw ApiException.forbidden(NOT_MEMBER, "User " + userId + " is not a member of your institution ('"
                    + ctx.institution() + "', group " + Py.repr(ctx.groupPath()) + ").");
        }
    }

    /**
     * Every id, de-duplicated, or 403 naming the ones that are not members (refused, never filtered: a
     * selection that silently shrank would act on less than the admin was shown). 400 for none.
     */
    public static List<String> assertAllMembers(InstitutionContext ctx, List<String> userIds,
                                                Map<String, ?> members) {
        List<String> wanted = new ArrayList<>(new LinkedHashSet<>(userIds == null ? List.<String>of()
                : userIds.stream().filter(u -> u != null && !u.isEmpty()).toList()));
        List<String> outside = wanted.stream().filter(u -> !members.containsKey(u)).toList();
        if (!outside.isEmpty()) {
            List<String> head = outside.subList(0, Math.min(10, outside.size()));
            throw ApiException.forbidden(NOT_MEMBER, outside.size() + " of the selected user(s) are not members of "
                    + "institution '" + ctx.institution() + "': " + Py.list(head)
                    + (outside.size() > 10 ? " …" : "") + ". Nothing was changed.");
        }
        if (wanted.isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "No users were selected.");
        }
        return wanted;
    }
}
