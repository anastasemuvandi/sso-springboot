package rw.gov.risa.sso.wrapper.service.institutionadmin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.institution.DelegatedExecution;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionContextService;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;

/**
 * Users and roles of ONE institution (port of the users/roles half of {@code InstitutionAdminService}).
 * Every method re-asserts its capability (the route's annotation is the first line, not the boundary),
 * then the per-object member check, then acts.
 *
 * <p><b>Credential</b>: object-level calls (read/create/update/disable a member, its groups, its role
 * mappings) run as the caller when {@code DELEGATED_EXECUTE_AS_CALLER} (FGAP v2 decides too); the realm
 * role catalogue is read with the service account (FGAP v2 has no {@code view} on roles) and filtered by
 * {@code may_map_role} before anything is shown or written.
 */
@Service
public class InstitutionUsersService {

    private static final Logger log = LoggerFactory.getLogger(InstitutionUsersService.class);

    /** Request fields for a create/update; null = not sent. */
    public record UserInput(String username, String email, String firstName, String lastName, Boolean enabled,
                            Boolean emailVerified, String nationalId, String phone) {
    }

    private final InstitutionKeycloak kc;
    private final InstitutionMembers members;
    private final DelegatedExecution delegated;
    private final JoinKeyService joinKeys;
    private final AuditEmitter audit;

    public InstitutionUsersService(InstitutionKeycloak kc, InstitutionMembers members, DelegatedExecution delegated,
                                   JoinKeyService joinKeys, AuditEmitter audit) {
        this.kc = kc;
        this.members = members;
        this.delegated = delegated;
        this.joinKeys = joinKeys;
        this.audit = audit;
    }

    // ---- users -----------------------------------------------------------------------------

    /** The institution's members only; {@code search} filters within them and never widens the scope. */
    public List<Map<String, Object>> listUsers(InstitutionContext ctx, CallerPrincipal caller, String search) {
        InstitutionContextService.requireCapability(ctx, Capability.VIEW_MEMBERS);
        KeycloakExec exec = delegated.forCaller(caller);
        List<UserRepresentation> users = new ArrayList<>(members.membersById(ctx, exec).values());
        if (search != null && !search.isEmpty()) {
            String q = search.toLowerCase(Locale.ROOT);
            users = users.stream().filter(u -> contains(u.getUsername(), q) || contains(u.getEmail(), q)
                    || contains(u.getFirstName(), q) || contains(u.getLastName(), q)).toList();
        }
        return users.stream().map(InstitutionViews::user).toList();
    }

    public Map<String, Object> getUser(InstitutionContext ctx, CallerPrincipal caller, String userId) {
        InstitutionContextService.requireCapability(ctx, Capability.VIEW_MEMBERS);
        KeycloakExec exec = delegated.forCaller(caller);
        members.assertMember(ctx, exec, userId);
        return detail(exec, userId);
    }

    /** Creates a user pinned to the caller's institution (attribute forced + group joined). */
    public Map<String, Object> createUser(InstitutionContext ctx, CallerPrincipal caller, UserInput in,
                                          String requestIp) {
        InstitutionContextService.requireCapability(ctx, Capability.MANAGE_MEMBERS);
        // Creating a member also places them in the group: the manage-membership scope.
        InstitutionContextService.requireCapability(ctx, Capability.MANAGE_MEMBERSHIP);
        KeycloakExec exec = delegated.forCaller(caller);
        String gid = members.groupId(ctx, exec);

        Map<String, List<String>> attributes = folded(in);
        attributes.put("institution", List.of(ctx.institution()));
        joinKeys.validateIdentifierWrite(in.email() != null, in.email(), attributes, null);

        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(in.username());
        rep.setEmail(in.email());
        rep.setFirstName(in.firstName());
        rep.setLastName(in.lastName());
        rep.setEnabled(in.enabled() == null || in.enabled());
        rep.setEmailVerified(Boolean.TRUE.equals(in.emailVerified()));
        rep.setAttributes(attributes);
        String id = kc.createUser(exec, rep);
        kc.joinGroup(exec, id, gid);

        audit.emit(AuditEvent.of(AuditEventType.ADMIN_USER_CREATED).actor(ctx.actor()).requestIp(requestIp)
                .institution(ctx.institution()).targetUser(id).put("target_username", in.username()).build());
        return detail(exec, id);
    }

    public Map<String, Object> updateUser(InstitutionContext ctx, CallerPrincipal caller, String userId,
                                          UserInput in, String requestIp) {
        InstitutionContextService.requireCapability(ctx, Capability.MANAGE_MEMBERS);
        KeycloakExec exec = delegated.forCaller(caller);
        members.assertMember(ctx, exec, userId);

        Map<String, List<String>> named = folded(in);
        if (!named.isEmpty()) {
            // Never moved out of (or into another) institution through the attribute.
            named.put("institution", List.of(ctx.institution()));
        }
        joinKeys.validateIdentifierWrite(in.email() != null, in.email(), named, userId);

        UserRepresentation current = kc.getUser(exec, userId)
                .orElseThrow(() -> ApiException.notFound("User '" + userId + "' not found."));
        if (in.email() != null) {
            current.setEmail(in.email());
        }
        if (in.firstName() != null) {
            current.setFirstName(in.firstName());
        }
        if (in.lastName() != null) {
            current.setLastName(in.lastName());
        }
        if (in.enabled() != null) {
            current.setEnabled(in.enabled());
        }
        if (in.emailVerified() != null) {
            current.setEmailVerified(in.emailVerified());
        }
        if (!named.isEmpty()) {
            // Keycloak's PUT replaces the whole map: merge, so IPPIS-sourced attributes survive.
            Map<String, List<String>> merged = new HashMap<>(current.getAttributes() == null ? Map.of()
                    : current.getAttributes());
            merged.putAll(named);
            current.setAttributes(merged);
        }
        kc.updateUser(exec, userId, current);
        audit.emit(AuditEvent.of(AuditEventType.ADMIN_USER_UPDATED).actor(ctx.actor()).requestIp(requestIp)
                .institution(ctx.institution()).targetUser(userId).build());
        return detail(exec, userId);
    }

    /** Disable the account and end its sessions (a failed logout does not undo the disable). */
    public Map<String, Object> deactivateUser(InstitutionContext ctx, CallerPrincipal caller, String userId,
                                              String requestIp) {
        InstitutionContextService.requireCapability(ctx, Capability.MANAGE_MEMBERS);
        KeycloakExec exec = delegated.forCaller(caller);
        members.assertMember(ctx, exec, userId);
        UserRepresentation current = kc.getUser(exec, userId)
                .orElseThrow(() -> ApiException.notFound("User '" + userId + "' not found."));
        current.setEnabled(false);
        kc.updateUser(exec, userId, current);
        try {
            kc.logout(exec, userId);
        } catch (RuntimeException e) {
            log.warn("institution admin: disabled {} but could not end its sessions: {}", userId, e.getMessage());
        }
        audit.emit(AuditEvent.of(AuditEventType.ADMIN_USER_DISABLED).actor(ctx.actor()).requestIp(requestIp)
                .institution(ctx.institution()).targetUser(userId).build());
        return detail(exec, userId);
    }

    // ---- roles -----------------------------------------------------------------------------

    /** Exactly the roles the caller's map-roles scoping permits (the same predicate as assignment). */
    public List<Map<String, Object>> assignableRoles(InstitutionContext ctx) {
        InstitutionContextService.requireCapability(ctx, Capability.MAP_ROLES);
        return kc.realmRoles(delegated.serviceAccount()).stream()
                .filter(r -> r.getName() != null && ctx.grant().mayMapRole(r.getName()))
                .sorted(java.util.Comparator.comparing(RoleRepresentation::getName))
                .map(InstitutionViews::role).toList();
    }

    public Map<String, Object> assignRole(InstitutionContext ctx, CallerPrincipal caller, String userId, String role,
                                          String requestIp) {
        KeycloakExec exec = delegated.forCaller(caller);
        members.assertMember(ctx, exec, userId);
        RoleRepresentation rep = roleRep(ctx, role);
        kc.addRealmRoles(exec, userId, List.of(rep));
        audit.emit(AuditEvent.of(AuditEventType.ADMIN_ROLE_ASSIGNED).actor(ctx.actor()).requestIp(requestIp)
                .institution(ctx.institution()).targetUser(userId).put("role", role).build());
        return roleResult("assigned", userId, role);
    }

    public Map<String, Object> revokeRole(InstitutionContext ctx, CallerPrincipal caller, String userId, String role,
                                          String requestIp) {
        KeycloakExec exec = delegated.forCaller(caller);
        members.assertMember(ctx, exec, userId);
        RoleRepresentation rep = roleRep(ctx, role);
        kc.removeRealmRoles(exec, userId, List.of(rep));
        audit.emit(AuditEvent.of(AuditEventType.ADMIN_ROLE_REMOVED).actor(ctx.actor()).requestIp(requestIp)
                .institution(ctx.institution()).targetUser(userId).put("role", role).build());
        return roleResult("revoked", userId, role);
    }

    /** 403 outside the namespace (checked FIRST), then 404 for a role that does not exist. */
    private RoleRepresentation roleRep(InstitutionContext ctx, String role) {
        ctx.assertRoleInNamespace(role);
        return kc.realmRoles(delegated.serviceAccount()).stream().filter(r -> role.equals(r.getName())).findFirst()
                .orElseThrow(() -> ApiException.notFound("Realm role " + Py.repr(role) + " does not exist."));
    }

    // ---- helpers ---------------------------------------------------------------------------

    private Map<String, Object> detail(KeycloakExec exec, String userId) {
        UserRepresentation u = kc.getUser(exec, userId)
                .orElseThrow(() -> ApiException.notFound("User '" + userId + "' not found."));
        return InstitutionViews.userDetail(u, kc.userGroups(exec, userId), kc.userRealmRoles(exec, userId));
    }

    /** {@code national_id}/{@code phone} folded into attributes; an unset or blank field is dropped. */
    static Map<String, List<String>> folded(UserInput in) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (in.nationalId() != null && !in.nationalId().isBlank()) {
            out.put("national_id", List.of(in.nationalId().strip()));
        }
        if (in.phone() != null && !in.phone().isBlank()) {
            out.put("phone", List.of(in.phone().strip()));
        }
        return out;
    }

    private static Map<String, Object> roleResult(String status, String userId, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("user_id", userId);
        m.put("role", role);
        return m;
    }

    private static boolean contains(String field, String q) {
        return field != null && field.toLowerCase(Locale.ROOT).contains(q);
    }
}
