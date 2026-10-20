package rw.gov.risa.sso.wrapper.service.institutionadmin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.mfa.policy.MfaConstants;
import rw.gov.risa.sso.mfa.policy.MfaPolicy;
import rw.gov.risa.sso.mfa.policy.SwitchState;
import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Holder;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites.WithdrawResult;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScopeException;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScopes;
import rw.gov.risa.sso.wrapper.policy.clientscope.DelegatedAppRules;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumn;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumnException;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinKeys;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews.AssignmentView;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService.VisibleClient;
import rw.gov.risa.sso.wrapper.service.institution.DelegatedExecution;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionContextService;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;

/**
 * The applications of ONE institution (port of the apps half of {@code InstitutionAdminService}): its
 * own {@code <slug>-*} clients plus the cross-cutting ones whose scope names it.
 *
 * <p>Every per-client operation first reads the client's scope from the catalogue (404 when absent),
 * then applies the cross-cutting refusal where the operation is platform-only (secret, join column,
 * MFA), then {@code assertClientInScope}. Only then does anything run.
 *
 * <p><b>Credential</b>: catalogue reads, client creation, secrets and client attribute writes (join
 * column, MFA) use the service account — Keycloak authorises them by realm-wide roles a delegated admin
 * structurally lacks, so the boundary is the wrapper's check that ran first. Access-policy changes on an
 * OWN client and group reads run as the caller; a cross-cutting client's policies use the service account.
 */
@Service
public class InstitutionAppsService {

    /** One row of {@code GET /apps}. */
    public record App(VisibleClient client, Map<String, Object> row) {
        public String clientId() {
            return client.clientId();
        }

        public boolean crossCutting() {
            return client.scope().crossCutting();
        }

        public boolean manageable() {
            return client.manageable();
        }

        public String name() {
            return client.client().getName();
        }
    }

    private final ClientScopeService scopes;
    private final ClientAccessService access;
    private final JoinKeyService joinKeys;
    private final MfaService mfa;
    private final InstitutionKeycloak kc;
    private final InstitutionMembers members;
    private final AccessHolders holders;
    private final DelegatedExecution delegated;
    private final AuditEmitter audit;

    public InstitutionAppsService(ClientScopeService scopes, ClientAccessService access, JoinKeyService joinKeys,
                                  MfaService mfa, InstitutionKeycloak kc, InstitutionMembers members,
                                  AccessHolders holders, DelegatedExecution delegated, AuditEmitter audit) {
        this.scopes = scopes;
        this.access = access;
        this.joinKeys = joinKeys;
        this.mfa = mfa;
        this.kc = kc;
        this.members = members;
        this.holders = holders;
        this.delegated = delegated;
        this.audit = audit;
    }

    // ---- listing ---------------------------------------------------------------------------

    /** Apps this institution may use. One catalogue read; the TOTP columns are informational. */
    public List<App> apps(InstitutionContext ctx) {
        InstitutionContextService.requireCapability(ctx, Capability.VIEW_CLIENTS);
        SwitchState state;
        try {
            state = mfa.currentState();
        } catch (RuntimeException e) {
            state = null;
        }
        List<App> out = new ArrayList<>();
        for (VisibleClient v : scopes.clientsVisibleTo(ctx)) {
            ClientRepresentation c = v.client();
            String raw = MfaPolicy.attribute(c.getAttributes() == null ? Map.of() : c.getAttributes(),
                    MfaConstants.CLIENT_ATTRIBUTE).orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("clientId", v.clientId());
            row.put("name", c.getName());
            row.put("manageable", v.manageable());
            row.put("cross_cutting", v.scope().crossCutting());
            row.put("owned", !v.scope().crossCutting());
            row.put("scope", v.scope().describe());
            row.put("join_column", JoinKeyService.settingOf(c).describe());
            row.put("mfa_required", MfaPolicy.clientRequires(raw));
            row.put("mfa_applies", state == null ? null : state.appliesTo(raw));
            row.put("mfa_mode", state == null ? null : state.mode().value());
            out.add(new App(v, row));
        }
        return out;
    }

    public List<Map<String, Object>> listApps(InstitutionContext ctx) {
        return apps(ctx).stream().map(App::row).toList();
    }

    // ---- creating --------------------------------------------------------------------------

    /**
     * Integrate an app bound to the caller's institution: the client-id is composed as
     * {@code <slug>-<app>} from the RESOLVED institution — there is no input that could name another one,
     * nor a cross-cutting flag. The composed id still goes through the caller's client scoping (a deny
     * pattern denies creating it).
     */
    public Map<String, Object> createApp(InstitutionContext ctx, String app, List<String> redirectUris,
                                         String postLogoutRedirectUris, String requestIp) {
        InstitutionContextService.requireCapability(ctx, Capability.MANAGE_CLIENTS);
        String clientId;
        try {
            clientId = ClientScopes.clientIdFor(ctx.institution(), app);
        } catch (ClientScopeException e) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, e.getMessage());
        }
        ctx.assertClientInScope(clientId, null);
        ClientScope scope = ClientScope.forInstitution(clientId, ctx.institution());

        ClientRepresentation rep = new ClientRepresentation();
        rep.setClientId(clientId);
        rep.setName(clientId);
        rep.setEnabled(true);
        rep.setPublicClient(false);
        rep.setProtocol("openid-connect");
        rep.setClientAuthenticatorType("client-secret");
        rep.setRedirectUris(List.copyOf(redirectUris));
        rep.setStandardFlowEnabled(true);
        rep.setDirectAccessGrantsEnabled(true);
        rep.setServiceAccountsEnabled(true);
        rep.setImplicitFlowEnabled(false);
        rep.setAuthorizationServicesEnabled(true);
        Map<String, String> attrs = new HashMap<>();
        attrs.put("pkce.code.challenge.method", "S256");
        if (postLogoutRedirectUris != null && !postLogoutRedirectUris.isBlank()) {
            attrs.put("post.logout.redirect.uris", postLogoutRedirectUris);
        }
        // Last, so a protocol default can never overwrite the scope; blank values are not stored.
        scope.toAttributes().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                attrs.put(k, v);
            }
        });
        rep.setAttributes(attrs);
        ClientRepresentation created = kc.createClient(delegated.serviceAccount(), rep);

        audit.emit(AuditEvent.of(AuditEventType.ADMIN_CLIENT_CREATED).actor(ctx.actor()).clientId(clientId)
                .requestIp(requestIp).institution(ctx.institution()).targetClient(clientId)
                .put("scope", scope.describe()).put("redirect_uris", redirectUris).build());
        Map<String, Object> out = InstitutionViews.client(created);
        out.put("scope", scope.describe());
        return out;
    }

    // ---- secret / join column / MFA (platform-only on a cross-cutting app) -----------------

    public Map<String, Object> appSecret(InstitutionContext ctx, String clientId) {
        ClientScope scope = scopes.appScope(clientId);
        refuseCrossCutting(scope, DelegatedAppRules.Operation.SECRET);
        ctx.assertClientInScope(clientId, scope);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client_secret", kc.clientSecret(delegated.serviceAccount(), clientId));
        return out;
    }

    /** Readable for a cross-cutting app too (it explains why staff without that identifier cannot sign in). */
    public Map<String, Object> joinColumn(InstitutionContext ctx, String clientId, boolean check) {
        ClientScope scope = scopes.appScope(clientId);
        ctx.assertClientInScope(clientId, scope, Capability.VIEW_CLIENTS);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("setting", joinKeys.getSetting(clientId).describe());
        out.put("choices", JoinKeys.choices());
        out.put("active_sessions", joinKeys.activeSessions(clientId));
        out.put("readiness", check ? joinKeys.readiness(clientId, null).describe() : null);
        return out;
    }

    public Map<String, Object> setJoinColumn(InstitutionContext ctx, String clientId, String column, boolean confirm,
                                             String requestIp) {
        InstitutionContextService.requireCapability(ctx, Capability.MANAGE_CLIENTS);
        JoinColumn requested;
        try {
            requested = JoinKeys.requested(column);
        } catch (JoinColumnException e) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, e.getMessage());
        }
        ClientScope scope = scopes.appScope(clientId);
        refuseCrossCutting(scope, DelegatedAppRules.Operation.JOIN_COLUMN);
        ctx.assertClientInScope(clientId, scope);

        JoinKeyService.JoinColumnChange r = joinKeys.setJoinColumn(clientId, requested, ctx.actor(), confirm);
        audit.emit(AuditEvent.of(AuditEventType.ADMIN_CLIENT_UPDATED).actor(ctx.actor()).clientId(clientId)
                .requestIp(requestIp).institution(ctx.institution()).targetClient(clientId)
                .put("action", "join_column_changed")
                .put("join_column_before", r.previous().column().value())
                .put("join_column_after", r.setting().column().value())
                .put("was_explicit", r.previous().explicit())
                .put("claim", r.setting().claim())
                .put("mapper", r.mapper())
                .put("active_sessions", r.activeSessions())
                .put("assigned_users", r.assignedUsers())
                .put("confirmed", confirm)
                .put("blocked_users", r.readiness().rows().size()).build());
        return r.describe();
    }

    public Map<String, Object> joinColumnBacklog(InstitutionContext ctx) {
        return joinKeys.backfillWorklist(ctx);
    }

    /** Require TOTP on one of the institution's OWN apps: ON only, never on a cross-cutting app. */
    public MfaService.ClientRequirementChange setMfaRequired(InstitutionContext ctx, String clientId, boolean required,
                                                            String requestIp) {
        InstitutionContextService.requireCapability(ctx, Capability.MANAGE_CLIENTS);
        MfaService.refuseDelegatedChange(required);
        ClientScope scope = scopes.appScope(clientId);
        refuseCrossCutting(scope, DelegatedAppRules.Operation.MFA);
        ctx.assertClientInScope(clientId, scope);
        return mfa.setClientRequirement(clientId, true, ctx.actor(), requestIp, ctx.institution());
    }

    // ---- institution-wide access -----------------------------------------------------------

    /** Add this institution's group to the client's access (additive: nobody else's access changes). */
    public AssignmentView grantAccess(InstitutionContext ctx, CallerPrincipal caller, String clientId,
                                      String requestIp) {
        ClientScope scope = scopes.appScope(clientId);
        ctx.assertClientInScope(clientId, scope);
        KeycloakExec callerExec = delegated.forCaller(caller);
        String gid = members.groupId(ctx, callerExec);
        KeycloakExec exec = holders.policiesExec(callerExec, scope.crossCutting());
        access.grantGroup(exec, clientId, gid);
        audit.emit(AuditEvent.of(AuditEventType.ADMIN_CLIENT_UPDATED).actor(ctx.actor()).clientId(clientId)
                .requestIp(requestIp).institution(ctx.institution()).targetClient(clientId)
                .put("action", "grant_access").put("cross_cutting", scope.crossCutting()).build());
        return access.describeAssignment(exec, clientId);
    }

    /** Withdraw this institution's group AND its individually named users; nobody else's. */
    public Map<String, Object> revokeAccess(InstitutionContext ctx, CallerPrincipal caller, String clientId,
                                            String requestIp) {
        ClientScope scope = scopes.appScope(clientId);
        ctx.assertClientInScope(clientId, scope);
        KeycloakExec callerExec = delegated.forCaller(caller);
        String gid = members.groupId(ctx, callerExec);
        KeycloakExec exec = holders.policiesExec(callerExec, scope.crossCutting());
        WithdrawResult r = access.removeInstitutionAccess(exec, clientId, ctx.institution(), gid);
        audit.emit(AuditEvent.of(AuditEventType.ADMIN_CLIENT_UPDATED).actor(ctx.actor()).clientId(clientId)
                .requestIp(requestIp).institution(ctx.institution()).targetClient(clientId)
                .put("action", "revoke_access").put("cross_cutting", scope.crossCutting()).build());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client_id", clientId);
        out.put("removed_groups", r.removedGroups().stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.id());
            m.put("name", g.name());
            return m;
        }).toList());
        out.put("removed_users", r.removedUserIds().stream().map(id -> Map.of("id", id)).toList());
        Map<String, Object> assignment = new LinkedHashMap<>();
        assignment.put("client_id", clientId);
        assignment.put("enforced", r.assignment().enforced());
        assignment.put("user_ids", r.assignment().userIds());
        assignment.put("group_ids", r.assignment().groupIds());
        assignment.put("denied_user_ids", r.assignment().deniedUserIds());
        out.put("assignment", assignment);
        return out;
    }

    // ---- who holds one app (flow A) --------------------------------------------------------

    /** Everyone in this institution who can currently use the app (view-clients AND view-members). */
    public Map<String, Object> appUsers(InstitutionContext ctx, CallerPrincipal caller, String clientId) {
        InstitutionContextService.requireCapability(ctx, Capability.VIEW_MEMBERS);
        ClientScope scope = scopes.appScope(clientId);
        ctx.assertClientInScope(clientId, scope, Capability.VIEW_CLIENTS);

        KeycloakExec exec = delegated.forCaller(caller);
        Map<String, UserRepresentation> memberMap = members.membersById(ctx, exec);
        Map<String, Holder> held = holders.holders(exec, clientId, memberMap, scope.crossCutting(),
                () -> members.memberGroupIds(ctx, exec));

        List<Map<String, Object>> items = new ArrayList<>();
        held.entrySet().stream()
                .sorted(Comparator.comparing(e -> nz(memberMap.get(e.getKey()).getUsername())))
                .forEach(e -> {
                    if (e.getValue().denied()) {
                        return;
                    }
                    UserRepresentation m = memberMap.get(e.getKey());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", e.getKey());
                    row.put("username", m.getUsername() == null ? e.getKey() : m.getUsername());
                    row.put("email", m.getEmail());
                    row.put("firstName", m.getFirstName());
                    row.put("lastName", m.getLastName());
                    row.put("enabled", m.isEnabled() == null || m.isEnabled());
                    row.put("direct", e.getValue().direct());
                    row.put("via_groups", List.copyOf(new java.util.LinkedHashSet<>(e.getValue().groupNames())));
                    items.add(row);
                });
        String name = apps(ctx).stream().filter(a -> a.clientId().equals(clientId)).map(App::name).findFirst()
                .orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client_id", clientId);
        out.put("name", name);
        out.put("institution", ctx.institution());
        out.put("cross_cutting", scope.crossCutting());
        out.put("revocable", ctx.grant().mayManageClient(clientId, ctx.shares(scope)));
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static void refuseCrossCutting(ClientScope scope, DelegatedAppRules.Operation op) {
        DelegatedAppRules.crossCuttingRefusal(scope, op).ifPresent(m -> {
            throw ApiException.forbidden(CROSS_CUTTING, m);
        });
    }

    /** 403 code: a platform-only operation on a cross-cutting app. */
    public static final String CROSS_CUTTING = "cross_cutting_client";

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
