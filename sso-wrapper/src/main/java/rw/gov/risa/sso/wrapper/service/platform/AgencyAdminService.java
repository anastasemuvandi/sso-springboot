package rw.gov.risa.sso.wrapper.service.platform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientMappingsRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.error.KeycloakErrors;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;

/**
 * {@code /agency/**} — agencies are top-level Keycloak groups, departments their children (port of
 * {@code api/agency.py} + {@code agency_service.py}). On Keycloak 26 a group's {@code subGroups} is
 * always empty: children are read from {@code /groups/{id}/children}. Service account throughout.
 */
@Service
public class AgencyAdminService {

    private static final Logger log = LoggerFactory.getLogger(AgencyAdminService.class);
    static final int MAX_MEMBERS = 10_000;

    private final PlatformSupport support;
    private final KeycloakDirectory directory;

    public AgencyAdminService(PlatformSupport support) {
        this.support = support;
        this.directory = support.directory();
    }

    // ---- views (reference schema/agency.py; camelCase on the wire) -------------------------

    public record SubgroupInfo(String id, String name, String path) {
    }

    public record MemberView(String id, String username, String email, String firstName, String lastName,
                             boolean enabled, Boolean emailVerified, Long createdTimestamp) {
    }

    public record AgencyView(String id, String name, String path, String type, String code,
                             Map<String, List<String>> attributes, int totalMembers, int activeMembers,
                             int inactiveMembers, List<MemberView> members, int totalClients,
                             List<String> assignedClients, Map<String, List<String>> clientRoles,
                             List<String> realmRoles, int totalSubgroups, List<SubgroupInfo> subGroups,
                             Long createdTimestamp) {
    }

    // ---- reads -----------------------------------------------------------------------------

    /** Every top-level group, enriched. */
    public List<AgencyView> list() {
        return support.sa().call(realm -> topLevel(realm).stream().map(g -> view(realm, g)).toList());
    }

    public Map<String, Object> count() {
        long n = support.sa().call(realm -> {
            Map<String, Long> c = realm.groups().count(true);
            Long v = c == null ? null : c.get("count");
            return v == null ? (long) topLevel(realm).size() : v;
        });
        return Map.of("count", n);
    }

    /** System-wide metrics; users in several agencies are counted once. */
    public Map<String, Object> metrics() {
        return support.sa().call(realm -> {
            List<GroupRepresentation> agencies = topLevel(realm);
            int active = 0;
            Set<String> users = new HashSet<>();
            for (GroupRepresentation g : agencies) {
                try {
                    List<UserRepresentation> members = directory.groupMembers(realm, g.getId(), MAX_MEMBERS);
                    if (!members.isEmpty()) {
                        active++;
                    }
                    members.forEach(m -> users.add(m.getId()));
                } catch (WebApplicationException e) {
                    log.debug("metrics: cannot read members of {}: {}", g.getId(), KeycloakErrors.status(e));
                }
            }
            int clients = directory.listClients(realm).size();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("totalAgencies", agencies.size());
            out.put("activeAgencies", active);
            out.put("totalUsers", users.size());
            out.put("totalApplications", clients);
            // The service account lives in one realm and cannot list realms; that realm exists.
            out.put("totalRealms", 1);
            out.put("totalClients", clients);
            return out;
        });
    }

    public AgencyView get(String groupId) {
        return support.sa().call(realm -> view(realm, support.requireGroup(realm, groupId)));
    }

    public List<MemberView> members(String groupId) {
        return support.sa().call(realm -> {
            support.requireGroup(realm, groupId);
            return directory.groupMembers(realm, groupId, MAX_MEMBERS).stream().map(AgencyAdminService::member).toList();
        });
    }

    public List<SubgroupInfo> subgroups(String groupId) {
        return support.sa().call(realm -> {
            support.requireGroup(realm, groupId);
            return directory.groupChildren(realm, groupId).stream()
                    .map(g -> new SubgroupInfo(g.getId(), g.getName(), g.getPath())).toList();
        });
    }

    private List<GroupRepresentation> topLevel(RealmResource realm) {
        List<GroupRepresentation> out = new ArrayList<>();
        int first = 0;
        while (true) {
            List<GroupRepresentation> page = realm.groups().groups(null, first, KeycloakDirectory.PAGE, false);
            out.addAll(page);
            if (page.size() < KeycloakDirectory.PAGE) {
                return out;
            }
            first += page.size();
        }
    }

    AgencyView view(RealmResource realm, GroupRepresentation g) {
        GroupResource resource = realm.groups().group(g.getId());
        List<MemberView> members = new ArrayList<>();
        try {
            directory.groupMembers(realm, g.getId(), MAX_MEMBERS).forEach(u -> members.add(member(u)));
        } catch (WebApplicationException e) {
            log.debug("agency view: cannot read members of {}", g.getId());
        }
        int active = (int) members.stream().filter(MemberView::enabled).count();
        List<String> realmRoles = new ArrayList<>();
        Map<String, List<String>> clientRoles = new LinkedHashMap<>();
        try {
            MappingsRepresentation m = resource.roles().getAll();
            if (m.getRealmMappings() != null) {
                m.getRealmMappings().forEach(r -> realmRoles.add(r.getName()));
            }
            if (m.getClientMappings() != null) {
                for (Map.Entry<String, ClientMappingsRepresentation> e : m.getClientMappings().entrySet()) {
                    List<String> names = new ArrayList<>();
                    if (e.getValue().getMappings() != null) {
                        e.getValue().getMappings().forEach(r -> names.add(r.getName()));
                    }
                    if (!names.isEmpty()) {
                        clientRoles.put(e.getValue().getClient() != null ? e.getValue().getClient() : e.getKey(), names);
                    }
                }
            }
        } catch (WebApplicationException e) {
            log.debug("agency view: cannot read role mappings of {}", g.getId());
        }
        List<SubgroupInfo> children = directory.groupChildren(realm, g.getId()).stream()
                .map(c -> new SubgroupInfo(c.getId(), c.getName(), c.getPath())).toList();
        Map<String, List<String>> attributes = g.getAttributes() == null ? Map.of() : g.getAttributes();
        String code = attributes.containsKey("code") && !attributes.get("code").isEmpty()
                ? attributes.get("code").get(0) : null;
        return new AgencyView(g.getId(), g.getName(), g.getPath(), groupType(g.getName(), attributes), code,
                attributes, members.size(), active, members.size() - active, members, clientRoles.size(),
                new ArrayList<>(clientRoles.keySet()), clientRoles, realmRoles, children.size(), children, null);
    }

    static MemberView member(UserRepresentation u) {
        boolean enabled = Boolean.TRUE.equals(u.isEnabled());
        return new MemberView(u.getId(), u.getUsername(), u.getEmail(), u.getFirstName(), u.getLastName(), enabled,
                Boolean.TRUE.equals(u.isEmailVerified()), u.getCreatedTimestamp());
    }

    /** {@code _determine_group_type}: the {@code type} attribute, else inferred from the name. */
    static String groupType(String name, Map<String, List<String>> attributes) {
        if (attributes != null && attributes.get("type") != null && !attributes.get("type").isEmpty()) {
            return attributes.get("type").get(0).toLowerCase(Locale.ROOT);
        }
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("ministry") || n.startsWith("min")) {
            return "ministry";
        }
        if (n.contains("department") || n.contains("dept")) {
            return "department";
        }
        if (n.contains("institution") || n.contains("institute")) {
            return "institution";
        }
        if (n.contains("agency")) {
            return "agency";
        }
        return "other";
    }

    // ---- writes ----------------------------------------------------------------------------

    public AgencyView create(String name, List<String> realmRoles, Map<String, List<String>> clientRoles,
                             Map<String, List<String>> attributes, CallerPrincipal caller) {
        MfaService.refuseExemptRole(realmRoles == null ? List.of() : realmRoles, "an agency (group) role assignment");
        AgencyView view = support.sa().call(realm -> {
            GroupRepresentation rep = new GroupRepresentation();
            rep.setName(name);
            rep.setAttributes(attributes == null ? Map.of() : attributes);
            String id;
            try (Response r = realm.groups().add(rep)) {
                id = created(r, "group '" + name + "'");
            }
            assignRoles(realm, id, realmRoles, clientRoles);
            return view(realm, realm.groups().group(id).toRepresentation());
        });
        support.emit(support.event(AuditEventType.ADMIN_GROUP_CREATED, caller).put("target", view.id())
                .put("agency_name", name).put("group_name", name).put("group_path", view.path())
                .putAll(rw.gov.risa.sso.wrapper.policy.audit.AuditScope.institutionField(
                        rw.gov.risa.sso.wrapper.policy.audit.AuditScope.institutionOfGroup(view.path(), name,
                                support.scopes().knownSlugs()))));
        return view;
    }

    /** Name/attributes update; realm roles and client roles, when sent, REPLACE the current ones. */
    public AgencyView update(String groupId, String name, List<String> realmRoles,
                             Map<String, List<String>> clientRoles, Map<String, List<String>> attributes,
                             CallerPrincipal caller) {
        if (realmRoles != null) {
            MfaService.refuseExemptRole(realmRoles, "an agency (group) role assignment");
        }
        AgencyView view = support.sa().call(realm -> {
            GroupRepresentation g = support.requireGroup(realm, groupId);
            GroupResource resource = realm.groups().group(groupId);
            if (name != null || attributes != null) {
                if (name != null) {
                    g.setName(name);
                }
                if (attributes != null) {
                    g.setAttributes(attributes);
                }
                try {
                    resource.update(g);
                } catch (WebApplicationException e) {
                    if (KeycloakErrors.status(e) == 409) {
                        throw ApiException.conflict(ErrorCodes.CONFLICT,
                                "A group named '" + name + "' already exists here.", Map.of());
                    }
                    throw e;
                }
            }
            if (realmRoles != null) {
                List<RoleRepresentation> existing = resource.roles().realmLevel().listAll();
                if (!existing.isEmpty()) {
                    resource.roles().realmLevel().remove(existing);
                }
                assignRoles(realm, groupId, realmRoles, null);
            }
            if (clientRoles != null) {
                MappingsRepresentation m = resource.roles().getAll();
                if (m.getClientMappings() != null) {
                    for (ClientMappingsRepresentation cm : m.getClientMappings().values()) {
                        if (cm.getMappings() != null && !cm.getMappings().isEmpty()) {
                            resource.roles().clientLevel(cm.getId()).remove(cm.getMappings());
                        }
                    }
                }
                assignRoles(realm, groupId, null, clientRoles);
            }
            return view(realm, resource.toRepresentation());
        });
        support.emit(support.event(AuditEventType.ADMIN_GROUP_UPDATED, caller).putAll(support.groupTarget(groupId))
                .put("group_id", groupId).put("new_name", name));
        return view;
    }

    public void delete(String groupId, CallerPrincipal caller) {
        GroupRepresentation g = support.sa().call(realm -> support.requireGroup(realm, groupId));
        Map<String, Object> target = support.groupTarget(g);
        support.sa().run(realm -> realm.groups().group(groupId).remove());
        support.emit(support.event(AuditEventType.ADMIN_GROUP_DELETED, caller).putAll(target).put("group_id", groupId));
    }

    public SubgroupInfo createSubgroup(String parentId, String name, Map<String, List<String>> attributes,
                                       CallerPrincipal caller) {
        SubgroupInfo info = support.sa().call(realm -> {
            support.requireGroup(realm, parentId);
            GroupRepresentation rep = new GroupRepresentation();
            rep.setName(name);
            rep.setAttributes(attributes == null ? Map.of() : attributes);
            String id;
            try (Response r = realm.groups().group(parentId).subGroup(rep)) {
                id = created(r, "subgroup '" + name + "'");
            }
            GroupRepresentation g = id != null ? realm.groups().group(id).toRepresentation()
                    : directory.groupChildren(realm, parentId).stream().filter(c -> name.equals(c.getName()))
                    .findFirst().orElseThrow(() -> ApiException.badGateway(ErrorCodes.KEYCLOAK_ERROR,
                            "Subgroup created but could not be read back"));
            return new SubgroupInfo(g.getId(), g.getName(), g.getPath());
        });
        support.emit(support.event(AuditEventType.ADMIN_GROUP_CREATED, caller).put("target", info.id())
                .put("parent_group_id", parentId).put("subgroup_name", name).put("group_path", info.path())
                .putAll(rw.gov.risa.sso.wrapper.policy.audit.AuditScope.institutionField(
                        rw.gov.risa.sso.wrapper.policy.audit.AuditScope.institutionOfGroup(info.path(), name,
                                support.scopes().knownSlugs()))));
        return info;
    }

    public void addMember(String groupId, String userId, CallerPrincipal caller) {
        support.sa().run(realm -> {
            support.requireGroup(realm, groupId);
            support.requireUser(realm, userId);
            realm.users().get(userId).joinGroup(groupId);
        });
        support.emit(support.event(AuditEventType.ADMIN_GROUP_MEMBER_ADDED, caller)
                .putAll(support.membershipTarget(userId, List.of(groupId))).put("group_id", groupId));
    }

    public void removeMember(String groupId, String userId, CallerPrincipal caller) {
        support.sa().run(realm -> {
            support.requireGroup(realm, groupId);
            support.requireUser(realm, userId);
            realm.users().get(userId).leaveGroup(groupId);
        });
        support.emit(support.event(AuditEventType.ADMIN_GROUP_MEMBER_REMOVED, caller)
                .putAll(support.membershipTarget(userId, List.of(groupId))).put("group_id", groupId));
    }

    /** Additive: unknown role names are skipped (as the reference); {@code mfa-exempt} is refused (400). */
    public AgencyView addRoles(String groupId, List<String> realmRoles, Map<String, List<String>> clientRoles,
                               CallerPrincipal caller) {
        MfaService.refuseExemptRole(realmRoles == null ? List.of() : realmRoles, "an agency (group) role assignment");
        AgencyView view = support.sa().call(realm -> {
            support.requireGroup(realm, groupId);
            assignRoles(realm, groupId, realmRoles, clientRoles);
            return view(realm, realm.groups().group(groupId).toRepresentation());
        });
        support.emit(support.event(AuditEventType.ADMIN_ROLE_ASSIGNED, caller).putAll(support.groupTarget(groupId))
                .put("group_id", groupId).put("realm_roles", realmRoles).put("client_roles", clientRoles)
                .put("scope", "group"));
        return view;
    }

    public AgencyView removeRoles(String groupId, List<String> realmRoles, Map<String, List<String>> clientRoles,
                                  CallerPrincipal caller) {
        AgencyView view = support.sa().call(realm -> {
            support.requireGroup(realm, groupId);
            GroupResource resource = realm.groups().group(groupId);
            if (realmRoles != null && !realmRoles.isEmpty()) {
                List<RoleRepresentation> reps = realmRoles(realm, realmRoles);
                if (!reps.isEmpty()) {
                    resource.roles().realmLevel().remove(reps);
                }
            }
            if (clientRoles != null) {
                clientRoles.forEach((clientId, names) -> {
                    ClientRepresentation c = support.findClient(realm, clientId).orElse(null);
                    if (c == null) {
                        return;
                    }
                    List<RoleRepresentation> reps = clientRoleReps(realm, c, names);
                    if (!reps.isEmpty()) {
                        resource.roles().clientLevel(c.getId()).remove(reps);
                    }
                });
            }
            return view(realm, resource.toRepresentation());
        });
        support.emit(support.event(AuditEventType.ADMIN_ROLE_REMOVED, caller).putAll(support.groupTarget(groupId))
                .put("group_id", groupId).put("realm_roles", realmRoles).put("client_roles", clientRoles)
                .put("scope", "group"));
        return view;
    }

    private void assignRoles(RealmResource realm, String groupId, List<String> realmRoles,
                             Map<String, List<String>> clientRoles) {
        GroupResource resource = realm.groups().group(groupId);
        if (realmRoles != null && !realmRoles.isEmpty()) {
            List<RoleRepresentation> reps = realmRoles(realm, realmRoles);
            if (!reps.isEmpty()) {
                resource.roles().realmLevel().add(reps);
            }
        }
        if (clientRoles != null) {
            clientRoles.forEach((clientId, names) -> {
                ClientRepresentation c = support.findClient(realm, clientId).orElse(null);
                if (c == null) {
                    log.warn("agency roles: client '{}' not found; skipped", clientId);
                    return;
                }
                List<RoleRepresentation> reps = clientRoleReps(realm, c, names);
                if (!reps.isEmpty()) {
                    resource.roles().clientLevel(c.getId()).add(reps);
                }
            });
        }
    }

    private List<RoleRepresentation> realmRoles(RealmResource realm, List<String> names) {
        List<RoleRepresentation> out = new ArrayList<>();
        for (String n : new java.util.LinkedHashSet<>(names)) {
            directory.realmRole(realm, n).ifPresentOrElse(out::add,
                    () -> log.warn("agency roles: realm role '{}' not found; skipped", n));
        }
        return out;
    }

    private static List<RoleRepresentation> clientRoleReps(RealmResource realm, ClientRepresentation c,
                                                           List<String> names) {
        Set<String> wanted = new HashSet<>(names == null ? List.of() : names);
        return realm.clients().get(c.getId()).roles().list().stream().filter(r -> wanted.contains(r.getName())).toList();
    }

    private static String created(Response r, String what) {
        int status = r.getStatus();
        if (status == 409) {
            throw ApiException.conflict(ErrorCodes.CONFLICT, "Failed to create " + what + ": it already exists.",
                    Map.of());
        }
        if (status < 200 || status >= 300) {
            String msg = PlatformSupport.errorMessage(r);
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                    "Failed to create " + what + ": " + (msg != null ? msg : "Keycloak returned " + status));
        }
        return PlatformSupport.createdId(r);
    }
}
