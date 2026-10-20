package rw.gov.risa.sso.wrapper.service.joinkey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.keycloak.admin.client.resource.ProtocolMappersResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.clientaccess.ClientAssignment;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.policy.joinkey.ColumnSpec;
import rw.gov.risa.sso.wrapper.policy.joinkey.IdentifierWriteCheck;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumn;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumnBacklog;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumnChangeGate;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumnSetting;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinKeyGate;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinKeyResolution;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinKeyResolver;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinKeyUser;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinKeys;
import rw.gov.risa.sso.wrapper.policy.joinkey.MapperReconciler;
import rw.gov.risa.sso.wrapper.policy.joinkey.MapperSpec;
import rw.gov.risa.sso.wrapper.policy.joinkey.ReadinessRow;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService;
import rw.gov.risa.sso.wrapper.service.institution.InstitutionContextService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;

/**
 * The Keycloak half of the join key (spec §5.6; port of {@code join_key_service.py}). Rules:
 * {@code policy.joinkey}. All Keycloak work uses the service account (writing a client attribute
 * and a protocol mapper needs realm-wide {@code manage-clients}; the delegated caller's scope check
 * happens before).
 */
@Service
public class JoinKeyService {

    private static final Logger log = LoggerFactory.getLogger(JoinKeyService.class);

    /** 400: a NID/phone/email write with an unusable value. */
    public static final String IDENTIFIER_MALFORMED = "identifier_malformed";
    /** 409: the identifier is already on another account. */
    public static final String IDENTIFIER_TAKEN = "identifier_taken";

    private final KeycloakExecutors executors;
    private final KeycloakDirectory directory;
    private final ClientAccessService access;
    private final ClientScopeService scopes;
    private final JoinKeyProperties props;
    private final List<String> exemptClients;
    private final AuditEmitter audit;

    public JoinKeyService(KeycloakExecutors executors, KeycloakDirectory directory, ClientAccessService access,
                          ClientScopeService scopes, JoinKeyProperties props, SsoProperties sso, AuditEmitter audit) {
        this.executors = executors;
        this.directory = directory;
        this.access = access;
        this.scopes = scopes;
        this.props = props;
        this.exemptClients = List.copyOf(sso.keycloak().exemptClients());
        this.audit = audit;
    }

    // ---- read models -----------------------------------------------------------------------

    /** A readiness scan: who among the client's assigned users the column would block. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Readiness(JoinColumnSetting setting, int checked, boolean truncated, List<ReadinessRow> rows) {
        /** {@code join_column, checked, blocked, truncated, users[]}. */
        public Map<String, Object> describe() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("join_column", setting.column().value());
            out.put("checked", checked);
            out.put("blocked", rows.size());
            out.put("truncated", truncated);
            out.put("users", rows.stream().map(ReadinessRow::describe).toList());
            return out;
        }
    }

    /** The client's admitted population (named users + members of assigned groups), capped. */
    public record AssignedPopulation(List<String> userIds, boolean truncated) {
        public int count() {
            return userIds.size();
        }
    }

    /** An applied join-column change. {@code mapper} = created | updated | unchanged. */
    public record JoinColumnChange(JoinColumnSetting setting, JoinColumnSetting previous, int activeSessions,
                                   int assignedUsers, String mapper, Readiness readiness) {
        /** {@code setting, previous, active_sessions, assigned_users, mapper, readiness} (wire shape). */
        public Map<String, Object> describe() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("setting", setting.describe());
            out.put("previous", previous.describe());
            out.put("active_sessions", activeSessions);
            out.put("assigned_users", assignedUsers);
            out.put("mapper", mapper);
            out.put("readiness", readiness.describe());
            return out;
        }
    }

    /** What the login gate evaluated (only when it applied and could evaluate). */
    public record JoinKeyCheck(JoinColumnSetting setting, JoinKeyResolution resolution) {
        public boolean ok() {
            return resolution.ok();
        }
    }

    // ---- the setting -----------------------------------------------------------------------

    /** The join column in force for a client, or 404. */
    public JoinColumnSetting getSetting(String clientId) {
        return settingOf(executors.serviceAccount().call(realm -> directory.requireClient(realm, clientId)));
    }

    /** The setting on an already-fetched client (no round trip). */
    public static JoinColumnSetting settingOf(ClientRepresentation client) {
        return JoinColumnSetting.parse(client.getClientId(), client.getAttributes());
    }

    /** Live sessions on the client; -1 when the count could not be read (404 for no client). */
    public int activeSessions(String clientId) {
        ClientRepresentation client = executors.serviceAccount().call(realm -> directory.requireClient(realm, clientId));
        try {
            long n = executors.serviceAccount().<Long>call(realm -> directory.activeSessionCount(realm, client.getId()));
            return (int) Math.min(Integer.MAX_VALUE, n);
        } catch (WebApplicationException | ProcessingException e) {
            log.warn("join key: could not count sessions on client {}: {}", clientId, e.getMessage());
            return -1;
        }
    }

    /**
     * {@code PUT …/join-column}: record the column AND reconcile the {@code sso-join-key} mapper in one
     * operation. 400 for a non-OIDC client or a failed attribute write; 409 {@code confirm_required}
     * (details = the gate's) when sessions are live or an already-configured client with assigned users is
     * re-keyed and {@code confirm} is false (a first-time set is not gated on users); 502 when the mapper
     * write fails. Readiness is reported, never enforced. Not audited here (the caller audits with context).
     */
    public JoinColumnChange setJoinColumn(String clientId, JoinColumn column, String actor, boolean confirm) {
        KeycloakExec sa = executors.serviceAccount();
        ClientRepresentation client = sa.call(realm -> directory.requireClient(realm, clientId));
        JoinColumnSetting before = settingOf(client);
        JoinColumnChangeGate.protocolRefusal(clientId, client.getProtocol()).ifPresent(m -> {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, m);
        });
        int sessions = activeSessions(clientId);
        AssignedPopulation assigned = JoinColumnChangeGate.isRekeying(before, column)
                ? assignedPopulation(clientId) : new AssignedPopulation(List.of(), false);
        JoinColumnChangeGate.Decision decision = JoinColumnChangeGate.evaluate(before, column, sessions,
                assigned.count(), assigned.truncated(), confirm);
        if (decision.confirmationRequired()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFIRM_REQUIRED, decision.message(),
                    decision.conflictDetails());
        }
        JoinColumnSetting after = decision.after();
        String mapper = sa.call(realm -> {
            ClientRepresentation fresh = realm.clients().get(client.getId()).toRepresentation();
            Map<String, String> attrs = new HashMap<>(fresh.getAttributes() == null ? Map.of() : fresh.getAttributes());
            attrs.putAll(after.toAttributes(actor, Instant.now()));
            fresh.setAttributes(attrs);
            try {
                realm.clients().get(client.getId()).update(fresh);
            } catch (WebApplicationException e) {
                log.error("join key: failed to record the column on {}: {}", clientId, e.getMessage());
                throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                        "Failed to record the join column on client '" + clientId + "'.");
            }
            return reconcileMapper(realm, client.getId(), after).code();
        });
        JoinColumnSetting stored = getSetting(clientId);
        Readiness readiness = readiness(clientId, column);
        log.info("join key: {} set client {} to '{}' (was '{}'{}); mapper {}; {} active session(s); {} assigned user(s) "
                        + "would be blocked", actor, clientId, column.value(), before.column().value(),
                before.explicit() ? "" : ", defaulted", mapper, sessions, readiness.rows().size());
        return new JoinColumnChange(stored, before, sessions, assigned.count(), mapper, readiness);
    }

    /**
     * Make the client's {@code sso-join-key} mapper match {@code setting} (matched by name; idempotent).
     * Any failure → 502: a recorded column whose tokens do not carry the claim is the silent failure
     * this feature exists to remove.
     */
    public MapperReconciler.Kind reconcileMapper(RealmResource realm, String clientUuid, JoinColumnSetting setting) {
        try {
            ProtocolMappersResource mappers = realm.clients().get(clientUuid).getProtocolMappers();
            List<MapperSpec> existing = new ArrayList<>();
            for (ProtocolMapperRepresentation m : mappers.getMappers()) {
                existing.add(new MapperSpec(m.getId(), m.getName(), m.getProtocol(), m.getProtocolMapper(),
                        m.getConfig()));
            }
            MapperReconciler.Action action = MapperReconciler.plan(setting, existing);
            switch (action.kind()) {
                case CREATED -> {
                    try (Response r = mappers.createMapper(toRep(action.write()))) {
                        if (r.getStatus() >= 300) {
                            throw new IllegalStateException("Keycloak answered " + r.getStatus());
                        }
                    }
                }
                case UPDATED -> mappers.update(action.write().id(), toRep(action.write()));
                case UNCHANGED -> {
                }
            }
            return action.kind();
        } catch (RuntimeException e) {
            log.error("join key: failed to write the {} mapper on client {}: {}", JoinKeys.JOIN_KEY_MAPPER_NAME,
                    clientUuid, e.getMessage());
            throw ApiException.badGateway(ErrorCodes.KEYCLOAK_ERROR, MapperReconciler.writeFailedMessage(setting.claim()));
        }
    }

    static ProtocolMapperRepresentation toRep(MapperSpec spec) {
        ProtocolMapperRepresentation rep = new ProtocolMapperRepresentation();
        rep.setId(spec.id());
        rep.setName(spec.name());
        rep.setProtocol(spec.protocol());
        rep.setProtocolMapper(spec.protocolMapper());
        rep.setConfig(new LinkedHashMap<>(spec.config()));
        return rep;
    }

    // ---- the assigned population -----------------------------------------------------------

    /**
     * Named users plus members of assigned groups, bounded by {@code JOIN_KEY_READINESS_LIMIT}. An
     * unreadable assignment yields an empty population (this feeds a warning and a report only).
     */
    public AssignedPopulation assignedPopulation(String clientId) {
        int limit = Math.max(1, props.readinessLimit());
        ClientAssignment assignment;
        try {
            assignment = access.assignment(executors.serviceAccount(), clientId);
        } catch (ApiException | WebApplicationException | ProcessingException e) {
            log.warn("join key: could not read the access assignment of {}: {}", clientId, e.getMessage());
            return new AssignedPopulation(List.of(), false);
        }
        List<String> ids = new ArrayList<>(assignment.userIds());
        executors.serviceAccount().run(realm -> {
            for (String gid : assignment.groupIds()) {
                if (ids.size() >= limit) {
                    break;
                }
                try {
                    directory.groupMembers(realm, gid, limit).forEach(m -> ids.add(m.getId()));
                } catch (WebApplicationException e) {
                    log.warn("join key: could not list members of group {}: {}", gid, e.getMessage());
                }
            }
        });
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        boolean truncated = unique.size() > limit;
        return new AssignedPopulation(truncated ? unique.subList(0, limit) : unique, truncated);
    }

    /** Which assigned users {@code column} (null = the current one) would block. */
    public Readiness readiness(String clientId, JoinColumn column) {
        JoinColumnSetting setting = getSetting(clientId);
        if (column != null) {
            setting = setting.withColumn(column);
        }
        AssignedPopulation population = assignedPopulation(clientId);
        List<JoinKeyUser> users = executors.serviceAccount().call(realm -> {
            List<JoinKeyUser> out = new ArrayList<>();
            for (String uid : population.userIds()) {
                try {
                    directory.getUser(realm, uid).ifPresent(u -> out.add(adapt(u)));
                } catch (WebApplicationException e) {
                    // a deleted user is not a finding
                }
            }
            return out;
        });
        List<ReadinessRow> rows = JoinKeyResolver.readiness(setting, users, props.validateFormat());
        return new Readiness(setting, users.size(), population.truncated(), rows);
    }

    // ---- resolving one user / the login gate -----------------------------------------------

    /** Whether this user has a usable key; the uniqueness search runs only for a well-formed value. */
    public JoinKeyResolution resolveForUser(RealmResource realm, UserRepresentation user, JoinColumnSetting setting) {
        JoinKeyUser subject = adapt(user);
        JoinKeyResolution first = JoinKeyResolver.resolve(setting, subject, null, props.validateFormat());
        if (!first.ok()) {
            return first;
        }
        List<JoinKeyUser> holders = holders(realm, setting.spec(), first.value());
        return JoinKeyResolver.resolve(setting, subject, holders, props.validateFormat());
    }

    /**
     * The login gate without the refusal: empty when it does not apply (no username, enforcement off,
     * exempt client, unset client unless {@code JOIN_KEY_ENFORCE_UNSET_CLIENTS}) or when evaluating it
     * failed on an infrastructure error (FAIL OPEN, logged).
     */
    public Optional<JoinKeyCheck> checkLogin(String username, String clientId) {
        if (!JoinKeyGate.appliesTo(username, clientId, props.enforce(), exemptClients)) {
            return Optional.empty();
        }
        try {
            return executors.serviceAccount().call(realm -> {
                JoinColumnSetting setting = settingOf(directory.requireClient(realm, clientId));
                if (!JoinKeyGate.enforcedFor(setting, props.enforceUnsetClients())) {
                    return Optional.<JoinKeyCheck>empty();
                }
                UserRepresentation brief = directory.findUserByUsername(realm, username)
                        .orElseThrow(() -> ApiException.notFound("User '" + username + "' not found."));
                // A brief listing may carry no attributes: read the full representation.
                UserRepresentation user = directory.getUser(realm, brief.getId()).orElse(brief);
                return Optional.of(new JoinKeyCheck(setting, resolveForUser(realm, user, setting)));
            });
        } catch (RuntimeException e) {
            log.warn("join key: could not evaluate the join key for {} on {} ({}); allowing the login", username,
                    clientId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The gate at a token door: on refusal audit {@code LOGIN_DENIED} and throw 403 with
     * {@code error = join_key_missing|join_key_invalid|join_key_not_unique} and details
     * {@code join_column, claim, user_attribute}. Returns the check when it applied and passed.
     */
    public Optional<JoinKeyCheck> enforceLogin(String username, String clientId, String requestIp, String grantType) {
        Optional<JoinKeyCheck> check = checkLogin(username, clientId);
        if (check.isEmpty() || check.get().ok()) {
            return check;
        }
        JoinColumnSetting setting = check.get().setting();
        JoinKeyResolution r = check.get().resolution();
        log.info("join key: denied {} on {} — {} ({})", username, clientId, r.error().code(), setting.column().value());
        audit.emit(AuditEvent.of(AuditEventType.LOGIN_DENIED).denied().actor(username).clientId(clientId)
                .requestIp(requestIp).targetClient(clientId)
                .put("reason", r.message()).put("join_column", setting.column().value())
                .put("join_key_error", r.error().code()).put("grant_type", grantType).build());
        Map<String, Object> details = new LinkedHashMap<>(JoinKeyGate.refusalBody(r, setting));
        details.remove("error");
        details.remove("error_description");
        throw new ApiException(HttpStatus.FORBIDDEN, r.error().code(), r.message(), details);
    }

    // ---- the write gate --------------------------------------------------------------------

    /**
     * Refuse a user write that would store an unusable NID / phone / email: 400
     * {@code identifier_malformed} (only when {@code JOIN_KEY_VALIDATE_FORMAT}), 409 {@code identifier_taken}
     * when another account holds it (naming neither). {@code selfUserId} = the account being updated
     * (null on create). A search that could not run is skipped, never a refusal. Not for the IPPIS reconcile.
     *
     * @param emailPresent whether the write body carries {@code email} at all
     * @param attributes   the body's {@code attributes} (only present keys are checked)
     */
    public void validateIdentifierWrite(boolean emailPresent, String email,
                                        Map<String, ? extends Collection<String>> attributes, String selfUserId) {
        List<IdentifierWriteCheck.Candidate> candidates = IdentifierWriteCheck.candidates(emailPresent, email, attributes);
        if (candidates.isEmpty()) {
            return;
        }
        for (IdentifierWriteCheck.Candidate c : candidates) {
            if (props.validateFormat()) {
                IdentifierWriteCheck.formatRefusal(c).ifPresent(r -> {
                    throw new ApiException(HttpStatus.BAD_REQUEST, IDENTIFIER_MALFORMED, r.message(),
                            Map.of("field", JoinKeys.specFor(c.column()).source(), "column", c.column().value()));
                });
            }
        }
        executors.serviceAccount().run(realm -> {
            for (IdentifierWriteCheck.Candidate c : candidates) {
                ColumnSpec spec = JoinKeys.specFor(c.column());
                List<JoinKeyUser> holders = holders(realm, spec, c.value());
                if (holders == null) {
                    log.warn("join key: could not check {} uniqueness on write", spec.source());
                    continue;
                }
                IdentifierWriteCheck.duplicateRefusal(c, holders, selfUserId).ifPresent(r -> {
                    throw new ApiException(HttpStatus.CONFLICT, IDENTIFIER_TAKEN, r.message(),
                            Map.of("field", spec.source(), "column", c.column().value()));
                });
            }
        });
    }

    /** {@link #validateIdentifierWrite(boolean, String, Map, String)} over a Keycloak user representation. */
    public void validateIdentifierWrite(UserRepresentation body, String selfUserId) {
        validateIdentifierWrite(body.getEmail() != null, body.getEmail(), body.getAttributes(), selfUserId);
    }

    // ---- the backfill worklist -------------------------------------------------------------

    /**
     * {@code GET /institution-admin/apps/join-columns}: every in-scope app's column and how much is
     * still unconfigured ({@code JoinColumnBacklog.summarise} shape). 403 without {@code view-clients}.
     */
    public Map<String, Object> backfillWorklist(InstitutionContext ctx) {
        InstitutionContextService.requireCapability(ctx, Capability.VIEW_CLIENTS);
        List<JoinColumnBacklog.App> apps = new ArrayList<>();
        for (ClientScopeService.VisibleClient v : scopes.clientsVisibleTo(ctx)) {
            apps.add(new JoinColumnBacklog.App(v.clientId(), v.client().getName(), v.owned(),
                    v.scope().crossCutting(), v.manageable(), settingOf(v.client())));
        }
        return JoinColumnBacklog.summarise(ctx.institution(), apps);
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Users holding {@code value} in the column; null when the search could not run. */
    private List<JoinKeyUser> holders(RealmResource realm, ColumnSpec spec, String value) {
        try {
            List<UserRepresentation> found = spec.isProperty()
                    ? directory.findUsersByEmail(realm, value, 5)
                    : directory.findUsersByAttribute(realm, spec.searchField(), value, 5);
            return found.stream().map(JoinKeyService::adapt).toList();
        } catch (WebApplicationException | ProcessingException e) {
            log.warn("join key: uniqueness search on {} failed: {}", spec.searchField(), e.getMessage());
            return null;
        }
    }

    public static JoinKeyUser adapt(UserRepresentation u) {
        return new JoinKeyUser(u.getId(), u.getUsername(), u.getEmail(), u.getAttributes());
    }
}
