package rw.gov.risa.sso.wrapper.api.institution;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessPolicy;
import rw.gov.risa.sso.wrapper.policy.clientaccess.CanonicalPolicies;
import rw.gov.risa.sso.wrapper.policy.clientaccess.CanonicalPolicies.PolicyChange;
import rw.gov.risa.sso.wrapper.service.clientaccess.AccessPolicyStore;
import rw.gov.risa.sso.wrapper.service.institutionadmin.InstitutionKeycloak;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.service.mail.MailDeliveryLog;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/**
 * Shared MockMvc fixture for every {@code /institution-admin/*} test (one Spring context for all of them):
 * the real resolver, controllers, institution-admin services, {@code ClientScopeService} and
 * {@code ClientAccessService}; Keycloak replaced by a small stateful fake realm:
 *
 * <ul>
 *   <li>groups {@code /minagri} (g-minagri) with department {@code /minagri/planning} (g-minagri-planning),
 *       {@code /minisante} (g-minisante);</li>
 *   <li>users: minagri — {@code u-a1} alice, {@code u-a2} andre (named on minagri-kubaka-web),
 *       {@code u-a3} amani (planning); minisante — {@code u-b1} bosco;</li>
 *   <li>clients: {@code minagri-kubaka-web} (owned by minagri; group g-minagri + user u-a2),
 *       {@code minisante-hmis} (owned by minisante; group g-minisante), {@code gov-portal} (cross-cutting for
 *       minagri + minisante; both groups), {@code gov-health} (cross-cutting, minisante only),
 *       {@code sso-admin-ui} (control plane, no scope).</li>
 * </ul>
 *
 * Access policies live in a fake {@link AccessPolicyStore} so the real deny-list writes can be asserted.
 * Every {@link KeycloakExec} handed out records whether it was the caller's ({@link #CALLER}) or the
 * service account's ({@link #SA}).
 */
@WrapperTest
public abstract class InstitutionAdminMvcSupport {

    public static final String A = "minagri";
    public static final String B = "minisante";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;

    @MockitoBean(answers = org.mockito.Answers.CALLS_REAL_METHODS)
    protected AuditEmitter audit;
    @MockitoBean
    protected KeycloakAdminFactory keycloakFactory;
    @MockitoBean
    protected KeycloakExecutors executors;
    @MockitoBean
    protected KeycloakDirectory directory;
    @MockitoBean
    protected AccessPolicyStore policyStore;
    @MockitoBean
    protected InstitutionKeycloak kc;
    @MockitoBean
    protected JoinKeyService joinKeys;
    @MockitoBean
    protected MfaService mfa;
    @MockitoBean
    protected MailDeliveryLog deliveries;
    @MockitoBean
    protected rw.gov.risa.sso.wrapper.service.platformops.audit.AuditServiceClient auditClient;

    protected static final RealmResource REALM = mock(RealmResource.class);
    /** The caller's own token (execute-as-caller). */
    protected static final KeycloakExec CALLER = KeycloakExec.of(REALM, true);
    /** The wrapper's service account. */
    protected static final KeycloakExec SA = KeycloakExec.of(REALM, false);

    protected final Map<String, GroupRepresentation> groups = new LinkedHashMap<>();
    protected final Map<String, UserRepresentation> users = new LinkedHashMap<>();
    protected final Map<String, List<String>> memberships = new LinkedHashMap<>();
    protected final Map<String, ClientRepresentation> clients = new LinkedHashMap<>();
    /** client uuid -> its authorization policies. */
    protected final Map<String, List<AccessPolicy>> policies = new HashMap<>();
    protected final List<RoleRepresentation> realmRoles = new ArrayList<>();
    protected final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    protected void fakeRealm() {
        groups.clear();
        users.clear();
        memberships.clear();
        clients.clear();
        policies.clear();
        realmRoles.clear();

        when(executors.serviceAccount()).thenReturn(SA);
        when(executors.asCaller(anyString())).thenReturn(CALLER);

        group("g-minagri", "minagri", "/minagri");
        group("g-minagri-planning", "planning", "/minagri/planning");
        group("g-minisante", "minisante", "/minisante");
        user("u-a1", "alice", A, "g-minagri");
        user("u-a2", "andre", A, "g-minagri");
        user("u-a3", "amani", A, "g-minagri-planning");
        user("u-b1", "bosco", B, "g-minisante");

        client("minagri-kubaka-web", Map.of("sso.client.owner_institution", A));
        client("minisante-hmis", Map.of("sso.client.owner_institution", B));
        client("gov-portal", Map.of("sso.client.cross_cutting", "true", "sso.client.institutions", "minagri##minisante"));
        client("gov-health", Map.of("sso.client.cross_cutting", "true", "sso.client.institutions", "minisante"));
        client("sso-admin-ui", Map.of());
        policies.put(uuid("minagri-kubaka-web"), new ArrayList<>(List.of(
                AccessPolicy.group("p1", CanonicalPolicies.GROUPS, false, List.of("g-minagri")),
                AccessPolicy.user("p2", CanonicalPolicies.USERS, false, List.of("u-a2")))));
        policies.put(uuid("minisante-hmis"), new ArrayList<>(List.of(
                AccessPolicy.group("p3", CanonicalPolicies.GROUPS, false, List.of("g-minisante")))));
        policies.put(uuid("gov-portal"), new ArrayList<>(List.of(
                AccessPolicy.group("p4", CanonicalPolicies.GROUPS, false, List.of("g-minagri", "g-minisante")))));
        policies.put(uuid("gov-health"), new ArrayList<>(List.of(
                AccessPolicy.group("p5", CanonicalPolicies.GROUPS, false, List.of("g-minisante")))));

        for (String slug : List.of(A, B)) {
            for (String tier : List.of("admin", "hr", "staff", "auditor")) {
                role("inst:" + slug + ":" + tier);
            }
        }
        role("super_admin");
        role("admin");
        role("mfa-exempt");

        stubDirectory();
        stubGateway();
        stubPolicyStore();
    }

    /** Matcher for {@code verify(audit).emit(auditEvent(e -> ...))} (emit is overloaded). */
    protected static rw.gov.risa.sso.wrapper.audit.AuditEvent auditEvent(
            org.mockito.ArgumentMatcher<rw.gov.risa.sso.wrapper.audit.AuditEvent> m) {
        return org.mockito.ArgumentMatchers.argThat(m);
    }

    // ---- fixture builders ------------------------------------------------------------------

    protected void group(String id, String name, String path) {
        GroupRepresentation g = new GroupRepresentation();
        g.setId(id);
        g.setName(name);
        g.setPath(path);
        groups.put(id, g);
    }

    protected void user(String id, String username, String institution, String groupId) {
        UserRepresentation u = new UserRepresentation();
        u.setId(id);
        u.setUsername(username);
        u.setEmail(username + "@" + institution + ".gov.rw");
        u.setFirstName(username);
        u.setLastName("Test");
        u.setEnabled(true);
        u.setAttributes(new HashMap<>(Map.of("institution", List.of(institution))));
        users.put(id, u);
        memberships.put(id, new ArrayList<>(List.of(groupId)));
    }

    protected void client(String clientId, Map<String, String> attributes) {
        ClientRepresentation c = new ClientRepresentation();
        c.setId(uuid(clientId));
        c.setClientId(clientId);
        c.setName(clientId);
        c.setEnabled(true);
        c.setPublicClient(false);
        c.setAttributes(new HashMap<>(attributes));
        clients.put(clientId, c);
    }

    protected void role(String name) {
        RoleRepresentation r = new RoleRepresentation();
        r.setId("r-" + name);
        r.setName(name);
        realmRoles.add(r);
    }

    protected static String uuid(String clientId) {
        return "c-" + clientId;
    }

    /** The users named on a client's canonical deny-list policy right now. */
    protected List<String> denied(String clientId) {
        return userIdsOf(clientId, CanonicalPolicies.DENIED_USERS);
    }

    protected List<String> named(String clientId) {
        return userIdsOf(clientId, CanonicalPolicies.USERS);
    }

    protected List<String> assignedGroups(String clientId) {
        return policies.getOrDefault(uuid(clientId), List.of()).stream()
                .filter(p -> CanonicalPolicies.GROUPS.equals(p.name()))
                .flatMap(p -> rw.gov.risa.sso.wrapper.policy.clientaccess.PolicyIds.groupIds(p.config().get("groups"))
                        .stream()).toList();
    }

    private List<String> userIdsOf(String clientId, String name) {
        return policies.getOrDefault(uuid(clientId), List.of()).stream().filter(p -> name.equals(p.name()))
                .flatMap(p -> rw.gov.risa.sso.wrapper.policy.clientaccess.PolicyIds.userIds(p.config().get("users"))
                        .stream()).toList();
    }

    // ---- stubs -----------------------------------------------------------------------------

    private void stubDirectory() {
        when(directory.listClients(any())).thenAnswer(i -> new ArrayList<>(clients.values()));
        when(directory.findClient(any(), anyString())).thenAnswer(i -> Optional.ofNullable(clients.get(i.<String>getArgument(1))));
        when(directory.requireClient(any(), anyString())).thenAnswer(i -> {
            ClientRepresentation c = clients.get(i.<String>getArgument(1));
            if (c == null) {
                throw ApiException.notFound("Client '" + i.getArgument(1) + "' not found.");
            }
            return c;
        });
        when(directory.getUser(any(), anyString())).thenAnswer(i -> Optional.ofNullable(users.get(i.<String>getArgument(1))));
        when(directory.getGroup(any(), anyString())).thenAnswer(i -> Optional.ofNullable(groups.get(i.<String>getArgument(1))));
        when(directory.userGroups(any(), anyString())).thenAnswer(i -> groupsOf(i.getArgument(1)));
    }

    private void stubGateway() {
        when(kc.groupByPath(any(), anyString())).thenAnswer(i -> groups.values().stream()
                .filter(g -> g.getPath().equals(i.getArgument(1))).findFirst());
        when(kc.groupChildren(any(), anyString())).thenAnswer(i -> {
            GroupRepresentation parent = groups.get(i.<String>getArgument(1));
            return groups.values().stream().filter(g -> parent != null && g.getPath().startsWith(parent.getPath() + "/")
                    && g.getPath().substring(parent.getPath().length() + 1).indexOf('/') < 0).toList();
        });
        when(kc.groupMembers(any(), anyString())).thenAnswer(i -> memberships.entrySet().stream()
                .filter(e -> e.getValue().contains(i.<String>getArgument(1))).map(e -> users.get(e.getKey())).toList());
        when(kc.groupName(any(), anyString())).thenAnswer(i -> {
            GroupRepresentation g = groups.get(i.<String>getArgument(1));
            return g == null ? null : g.getName();
        });
        when(kc.userGroups(any(), anyString())).thenAnswer(i -> {
            if (!users.containsKey(i.<String>getArgument(1))) {
                throw ApiException.notFound("User '" + i.getArgument(1) + "' not found.");
            }
            return groupsOf(i.getArgument(1));
        });
        when(kc.getUser(any(), anyString())).thenAnswer(i -> Optional.ofNullable(users.get(i.<String>getArgument(1))));
        when(kc.userRealmRoles(any(), anyString())).thenReturn(List.of());
        when(kc.realmRoles(any())).thenAnswer(i -> List.copyOf(realmRoles));
        when(kc.createUser(any(), any())).thenAnswer(i -> {
            UserRepresentation u = i.getArgument(1);
            String id = "u-new-" + ids.incrementAndGet();
            u.setId(id);
            users.put(id, u);
            memberships.put(id, new ArrayList<>());
            return id;
        });
        doAnswer(i -> memberships.get(i.<String>getArgument(1)).add(i.getArgument(2))).when(kc)
                .joinGroup(any(), anyString(), anyString());
        doAnswer(i -> users.put(i.getArgument(1), i.getArgument(2))).when(kc).updateUser(any(), anyString(), any());
        when(kc.createClient(any(), any())).thenAnswer(i -> {
            ClientRepresentation c = i.getArgument(1);
            c.setId(uuid(c.getClientId()));
            clients.put(c.getClientId(), c);
            return c;
        });
        when(kc.clientSecret(any(), anyString())).thenReturn("s3cret");
        when(kc.clientSessions(any())).thenReturn(Map.of());
        when(kc.loginEvents(any(), anyInt(), anyInt())).thenReturn(List.of());
    }

    private void stubPolicyStore() {
        when(policyStore.read(any(), anyString()))
                .thenAnswer(i -> List.copyOf(policies.getOrDefault(i.<String>getArgument(1), List.of())));
        doAnswer(i -> {
            String uuid = i.getArgument(1);
            List<PolicyChange> changes = i.getArgument(2);
            List<AccessPolicy> current = policies.computeIfAbsent(uuid, k -> new ArrayList<>());
            for (PolicyChange ch : changes) {
                switch (ch.op()) {
                    case DELETE -> current.removeIf(p -> p.id().equals(ch.existingId()));
                    case CREATE, UPDATE -> {
                        if (ch.existingId() != null) {
                            current.removeIf(p -> p.id().equals(ch.existingId()));
                        }
                        var b = ch.body();
                        boolean negative = AccessPolicy.LOGIC_NEGATIVE.equals(b.logic());
                        String id = ch.existingId() != null ? ch.existingId() : "p-" + b.name() + "-" + ids.incrementAndGet();
                        current.add(AccessPolicy.TYPE_GROUP.equals(b.type())
                                ? AccessPolicy.group(id, b.name(), negative, b.groupIds())
                                : AccessPolicy.user(id, b.name(), negative, b.userIds()));
                    }
                }
            }
            return null;
        }).when(policyStore).apply(any(), anyString(), anyList());
    }

    private List<GroupRepresentation> groupsOf(String userId) {
        return memberships.getOrDefault(userId, List.of()).stream().map(groups::get).toList();
    }
}
