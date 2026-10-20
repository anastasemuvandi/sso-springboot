package rw.gov.risa.sso.wrapper.service.clientscope;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.WebApplicationException;

import org.keycloak.representations.idm.ClientRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAccessWrites;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScopeException;
import rw.gov.risa.sso.wrapper.policy.clientscope.ScopeChangePolicy;
import rw.gov.risa.sso.wrapper.policy.clientscope.ScopeChangePolicy.InstitutionAccess;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionRegistryHolder;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;

/**
 * Who owns / may use a client (spec §5.5; port of {@code client_scope.py}'s callers). The rules are
 * {@code policy.clientscope}; this reads/writes the three {@code sso.client.*} attributes with the
 * service account and runs the scope-change two-step (409 → confirm → withdraw).
 */
@Service
public class ClientScopeService {

    private static final Logger log = LoggerFactory.getLogger(ClientScopeService.class);

    private final KeycloakExecutors executors;
    private final KeycloakDirectory directory;
    private final InstitutionRegistryHolder registry;
    private final ClientAccessService access;
    private final AuditEmitter audit;

    public ClientScopeService(KeycloakExecutors executors, KeycloakDirectory directory,
                              InstitutionRegistryHolder registry, ClientAccessService access, AuditEmitter audit) {
        this.executors = executors;
        this.directory = directory;
        this.registry = registry;
        this.access = access;
        this.audit = audit;
    }

    /** The scope plus, per covered institution, who is on the client ({@code GET …/scope}). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScopeState(Map<String, Object> scope, List<Map<String, Object>> access) {
    }

    /** Result of an applied scope change ({@code PUT …/scope}). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScopeChange(Map<String, Object> scope, Map<String, Object> previous,
                              List<String> removedInstitutions, List<Map<String, Object>> accessWithdrawn) {
    }

    /**
     * One client an institution may see ({@code list_apps}): {@code owned} = not cross-cutting;
     * {@code manageable} = the caller's grant may manage it; {@code shared} = cross-cutting and covers
     * the institution.
     */
    public record VisibleClient(ClientRepresentation client, ClientScope scope, boolean shared, boolean manageable,
                                boolean owned) {
        public String clientId() {
            return client.getClientId();
        }
    }

    // ---- reading ---------------------------------------------------------------------------

    public Set<String> knownSlugs() {
        return registry.knownSlugs();
    }

    /** The scope a client representation records (prefix inference against the live registry). */
    public ClientScope scopeOf(ClientRepresentation client) {
        String id = client.getClientId() != null ? client.getClientId() : client.getId();
        ClientScope scope = ClientScope.parse(id, client.getAttributes(), knownSlugs());
        if (scope.coversNobody()) {
            log.warn("client scope: {} is cross-cutting with no institution assigned — nobody can use it", id);
        }
        return scope;
    }

    /** The client's scope, or 404 (service account). */
    public ClientScope getScope(String clientId) {
        return scopeOf(executors.serviceAccount().call(realm -> directory.requireClient(realm, clientId)));
    }

    /** {@code GET /client/clients/{id}/scope}. */
    public ScopeState scopeState(String clientId) {
        ClientScope scope = getScope(clientId);
        List<String> covered = scope.covered(knownSlugs());
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!covered.isEmpty()) {
            Map<String, ClientAccessWrites.InstitutionAccess> summary =
                    access.institutionAccessSummary(executors.serviceAccount(), clientId, covered);
            for (String slug : covered) {
                ClientAccessWrites.InstitutionAccess a = summary.get(slug);
                if (a != null) {
                    rows.add(new InstitutionAccess(a.institution(), a.groupAssigned(), a.users()).describe());
                }
            }
        }
        return new ScopeState(scope.describe(), rows);
    }

    /**
     * Clients the institution may see: its own ({@code <slug>-*} per the templates) plus cross-cutting
     * ones whose scope names it. One catalogue read (service account).
     */
    public List<VisibleClient> clientsVisibleTo(InstitutionContext ctx) {
        List<ClientRepresentation> clients = executors.serviceAccount().call(directory::listClients);
        List<VisibleClient> out = new ArrayList<>();
        for (ClientRepresentation c : clients) {
            String clientId = c.getClientId() == null ? "" : c.getClientId();
            ClientScope scope = scopeOf(c);
            boolean shared = ctx.shares(scope);
            if (!ctx.grant().mayViewClient(clientId, shared)) {
                continue;
            }
            out.add(new VisibleClient(c, scope, shared, ctx.grant().mayManageClient(clientId, shared),
                    !scope.crossCutting()));
        }
        return out;
    }

    /** {@code _app_scope}: one client's scope from the catalogue, or 404 "does not exist". */
    public ClientScope appScope(String clientId) {
        return executors.serviceAccount().call(realm -> directory.findClient(realm, clientId))
                .map(this::scopeOf)
                .orElseThrow(() -> ApiException.notFound("Client '" + clientId + "' does not exist."));
    }

    // ---- writing ---------------------------------------------------------------------------

    /** Validate a requested scope (create / {@code PUT …/scope}); 400 {@code invalid_request} when refused. */
    public ClientScope requested(String clientId, boolean crossCutting, Collection<String> institutions,
                                 String ownerInstitution) {
        try {
            return ClientScope.requested(clientId, crossCutting, institutions, ownerInstitution, knownSlugs());
        } catch (ClientScopeException e) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, e.getMessage());
        }
    }

    /**
     * Record a scope on the client (read-modify-write of the attribute map; every owned key written,
     * empty when unset, so a switch clears the old scope). Returns the stored scope.
     */
    public ClientScope writeScope(String clientId, ClientScope scope) {
        KeycloakExec sa = executors.serviceAccount();
        return sa.call(realm -> {
            ClientRepresentation c = directory.requireClient(realm, clientId);
            Map<String, String> attrs = new HashMap<>(c.getAttributes() == null ? Map.of() : c.getAttributes());
            attrs.putAll(scope.toAttributes());
            c.setAttributes(attrs);
            try {
                realm.clients().get(c.getId()).update(c);
            } catch (WebApplicationException e) {
                log.error("client scope: failed to set the scope on {}: {}", clientId, e.getMessage());
                throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                        "Failed to set the institution scope on client '" + clientId + "'.");
            }
            return scopeOf(directory.requireClient(realm, clientId));
        });
    }

    /**
     * Decide a scope change without writing: removed institutions, and (only for those) what each has
     * on the client. {@code confirmationRequired} ⇒ the caller answers 409.
     */
    public ScopeChangePolicy.Plan planChange(String clientId, ClientScope before, ClientScope after, boolean confirm) {
        Set<String> known = knownSlugs();
        List<String> losing = ScopeChangePolicy.removedInstitutions(before, after, known);
        Map<String, InstitutionAccess> impact = new LinkedHashMap<>();
        if (!losing.isEmpty()) {
            access.institutionAccessSummary(executors.serviceAccount(), clientId, losing)
                    .forEach((slug, a) -> impact.put(slug, new InstitutionAccess(a.institution(), a.groupAssigned(),
                            a.users())));
        }
        return ScopeChangePolicy.evaluate(before, after, known, impact, confirm);
    }

    /**
     * {@code PUT /client/clients/{id}/scope} (platform admins only — the caller checks the tier):
     * 409 {@code confirm_required} (details = the impact) before removing an institution that has users
     * on the client; with {@code confirm} it writes the scope and WITHDRAWS each removed institution's
     * access (its group + its named users). Widening takes nothing away. Audited
     * ({@code admin.client.scope_changed} + one {@code scope_institution_removed} per removed institution).
     */
    public ScopeChange changeScope(String clientId, ClientScope after, boolean confirm, String actor, String requestIp) {
        ClientScope before = getScope(clientId);
        ScopeChangePolicy.Plan plan = planChange(clientId, before, after, confirm);
        if (plan.confirmationRequired()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFIRM_REQUIRED, ScopeChangePolicy.CONFIRM_MESSAGE,
                    plan.conflictDetails());
        }
        ClientScope stored = writeScope(clientId, after);

        List<Map<String, Object>> withdrawn = new ArrayList<>();
        for (String slug : plan.removedInstitutions()) {
            ClientAccessWrites.WithdrawResult r = access.removeInstitutionAccess(executors.serviceAccount(), clientId,
                    slug, null);
            if (r.changed()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("institution", slug);
                row.put("group_assigned", !r.removedGroups().isEmpty());
                row.put("users", r.removedUserIds().size());
                withdrawn.add(row);
            }
        }
        if (!plan.removedInstitutions().isEmpty()) {
            log.info("client scope: {} removed {} from {}; access withdrawn for {}", actor, plan.removedInstitutions(),
                    clientId, withdrawn.isEmpty() ? "none (nobody was assigned)" : withdrawn);
        }
        audit.emit(AuditEvent.of(AuditEventType.ADMIN_CLIENT_SCOPE_CHANGED).actor(actor).clientId(clientId)
                .requestIp(requestIp).targetClient(clientId)
                .institution(stored.owner())
                .put("action", "scope_changed")
                .put("scope_before", before.describe())
                .put("scope_after", stored.describe())
                .put("removed_institutions", plan.removedInstitutions())
                .put("access_withdrawn", withdrawn)
                .put("timestamp", Instant.now().toString()).build());
        for (String slug : plan.removedInstitutions()) {
            Map<String, Object> lost = withdrawn.stream().filter(w -> slug.equals(w.get("institution"))).findFirst()
                    .orElse(null);
            audit.emit(AuditEvent.of(AuditEventType.ADMIN_CLIENT_SCOPE_CHANGED).actor(actor).clientId(clientId)
                    .requestIp(requestIp).targetClient(clientId).institution(slug)
                    .put("action", "scope_institution_removed")
                    .put("group_assigned", lost != null && Boolean.TRUE.equals(lost.get("group_assigned")))
                    .put("users_withdrawn", lost == null ? 0 : lost.get("users")).build());
        }
        return new ScopeChange(stored.describe(), before.describe(), plan.removedInstitutions(), withdrawn);
    }
}
