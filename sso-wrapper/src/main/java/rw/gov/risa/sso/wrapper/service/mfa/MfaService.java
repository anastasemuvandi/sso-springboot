package rw.gov.risa.sso.wrapper.service.mfa;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import rw.gov.risa.sso.mfa.policy.MfaConstants;
import rw.gov.risa.sso.mfa.policy.MfaMode;
import rw.gov.risa.sso.mfa.policy.MfaPolicy;
import rw.gov.risa.sso.mfa.policy.SwitchState;
import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;

/**
 * The realm TOTP switch, the per-client requirement, exemptions and the password-grant door (spec
 * §5.7; port of {@code mfa_service.py}). THE rule is the shared {@code mfa-policy} module
 * ({@link MfaPolicy}, {@link SwitchState}) — never re-implemented here. Keycloak enforces at login
 * (the {@code browser-totp} flow); this service changes the attributes (service account, needs
 * {@code manage-realm}/{@code manage-clients}/{@code manage-users}) and audits every change with actor,
 * old value, new value and time.
 */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);
    static final Duration STATE_TTL = Duration.ofSeconds(5);

    private final KeycloakExecutors executors;
    private final KeycloakDirectory directory;
    private final MfaBulkEnrolment bulk;
    private final AuditEmitter audit;
    private final Clock clock;

    private volatile Cached<SwitchState> state;
    private final Map<String, Cached<Optional<String>>> clientValues = new ConcurrentHashMap<>();

    private record Cached<T>(T value, Instant at) {
    }

    @Autowired
    public MfaService(KeycloakExecutors executors, KeycloakDirectory directory, MfaBulkEnrolment bulk,
                      AuditEmitter audit) {
        this(executors, directory, bulk, audit, Clock.systemUTC());
    }

    MfaService(KeycloakExecutors executors, KeycloakDirectory directory, MfaBulkEnrolment bulk, AuditEmitter audit,
               Clock clock) {
        this.executors = executors;
        this.directory = directory;
        this.bulk = bulk;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- results ---------------------------------------------------------------------------

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModeChange(String mode, String previousMode, boolean enforced, boolean previous, boolean changed,
                             Map<String, Object> bulkEnrol) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ClientRequirement(String clientId, boolean required, String raw, String mode, boolean appliesNow) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ClientRequirementChange(String clientId, boolean required, boolean previous, boolean changed,
                                          String mode, boolean appliesNow) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Exemption(String userId, String username, String reason, String grantedBy, String grantedAt) {
    }

    // ---- the switch ------------------------------------------------------------------------

    /** {@code GET /realm/mfa}: {@link SwitchState#describe()} + {@code first_enabled_at} + {@code exempt_count}. */
    public Map<String, Object> getState() {
        return executors.serviceAccount().call(realm -> {
            RealmRepresentation rep = realm.toRepresentation();
            SwitchState s = switchState(rep);
            Map<String, Object> out = new LinkedHashMap<>(s.describe());
            out.put("first_enabled_at", attr(rep, MfaConstants.FIRST_ENABLED_ATTRIBUTE));
            out.put("exempt_count", directory.realmRoleUsers(realm, MfaConstants.EXEMPT_ROLE).size());
            return out;
        });
    }

    /** The switch as the login gate sees it, cached 5 s (a flip is still seen within seconds). */
    public SwitchState currentState() {
        Cached<SwitchState> c = state;
        if (c != null && c.at().plus(STATE_TTL).isAfter(clock.instant())) {
            return c.value();
        }
        SwitchState s = executors.serviceAccount().call(realm -> switchState(realm.toRepresentation()));
        state = new Cached<>(s, clock.instant());
        return s;
    }

    /** On = {@code all}, off = {@code off} (the original single switch). */
    public ModeChange setEnforced(boolean enforced, String actor, String requestIp) {
        return setMode(enforced ? MfaMode.ALL.value() : MfaMode.OFF.value(), actor, requestIp);
    }

    /**
     * {@code PUT /realm/mfa} (super admin): 400 for an unknown mode; 409 when turning TOTP on while the
     * realm's browser flow is not {@code browser-totp}. Written whenever the stored value differs, audited
     * even when unchanged. The FIRST time the mode becomes {@code all} the bulk CONFIGURE_TOTP enrolment is
     * started (never for exempt users).
     */
    public ModeChange setMode(String mode, String actor, String requestIp) {
        MfaMode requested;
        try {
            requested = MfaPolicy.requestedMode(mode);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, e.getMessage());
        }
        return executors.serviceAccount().call(realm -> {
            RealmRepresentation rep = realm.toRepresentation();
            SwitchState s = switchState(rep);
            MfaMode previous = s.mode();
            if (requested != MfaMode.OFF && !s.flowReady()) {
                throw ApiException.conflict(ErrorCodes.CONFLICT, "The realm's browser flow is '" + s.browserFlow()
                        + "', not '" + MfaConstants.MFA_FLOW_ALIAS + "', so turning TOTP on would change nothing at "
                        + "login. Apply infra/terraform with enable_mfa_totp = true (or the MFA flow installer) first.",
                        Map.of());
            }
            String raw = s.modeRaw() == null ? "" : s.modeRaw().strip().toLowerCase(java.util.Locale.ROOT);
            boolean changed = !raw.equals(requested.value());
            String ts = Instant.now(clock).toString();
            Map<String, Object> bulkResult = null;
            String firstEnabled = attr(rep, MfaConstants.FIRST_ENABLED_ATTRIBUTE);
            boolean startBulk = requested == MfaMode.ALL && (firstEnabled == null || firstEnabled.isBlank());
            if (changed || startBulk) {
                Map<String, String> attrs = new HashMap<>(rep.getAttributes() == null ? Map.of() : rep.getAttributes());
                if (changed) {
                    attrs.put(MfaConstants.MODE_ATTRIBUTE, requested.value());
                }
                if (startBulk) {
                    attrs.put(MfaConstants.FIRST_ENABLED_ATTRIBUTE, ts);
                }
                rep.setAttributes(attrs);
                realm.update(rep);
                invalidate();
            }
            audit.emit(AuditEvent.of(AuditEventType.MFA_SWITCH_CHANGED).actor(actor).requestIp(requestIp)
                    .put("target", MfaConstants.MODE_ATTRIBUTE)
                    .put("old_value", previous.value()).put("new_value", requested.value())
                    .put("old_raw", s.modeRaw()).put("changed", changed).put("timestamp", ts).build());
            if (startBulk) {
                bulkResult = bulk.startAsync(actor);
            }
            return new ModeChange(requested.value(), previous.value(), requested != MfaMode.OFF,
                    previous != MfaMode.OFF, changed, bulkResult);
        });
    }

    // ---- per client ------------------------------------------------------------------------

    /** {@code GET …/mfa}: the client's own setting (unset = required) and whether it applies now. */
    public ClientRequirement clientRequirement(String clientId) {
        return executors.serviceAccount().call(realm -> {
            ClientRepresentation c = directory.requireClient(realm, clientId);
            SwitchState s = switchState(realm.toRepresentation());
            String raw = clientValue(c).orElse(null);
            return new ClientRequirement(clientId, MfaPolicy.clientRequires(raw), raw, s.mode().value(),
                    s.appliesTo(raw));
        });
    }

    /**
     * Set {@code sso.client.mfa_required}. WHO may call is the caller's decision (super-admin route; the
     * delegated route first calls {@link #refuseDelegatedChange}). Audited with old/new value and the
     * institution ({@code institution}, else the client's owner attribute).
     */
    public ClientRequirementChange setClientRequirement(String clientId, boolean required, String actor,
                                                        String requestIp, String institution) {
        return executors.serviceAccount().call(realm -> {
            ClientRepresentation c = directory.requireClient(realm, clientId);
            String rawBefore = clientValue(c).orElse(null);
            boolean before = MfaPolicy.clientRequires(rawBefore);
            String newRaw = MfaPolicy.encode(required);
            boolean changed = !newRaw.equals(rawBefore);
            if (changed) {
                Map<String, String> attrs = new HashMap<>(c.getAttributes() == null ? Map.of() : c.getAttributes());
                attrs.put(MfaConstants.CLIENT_ATTRIBUTE, newRaw);
                c.setAttributes(attrs);
                realm.clients().get(c.getId()).update(c);
                invalidate();
            }
            SwitchState s = switchState(realm.toRepresentation());
            String owner = institution != null ? institution
                    : c.getAttributes() == null ? null : blankToNull(c.getAttributes().get("sso.client.owner_institution"));
            audit.emit(AuditEvent.of(AuditEventType.MFA_CLIENT_REQUIREMENT_CHANGED).actor(actor).clientId(clientId)
                    .requestIp(requestIp).targetClient(clientId).institution(owner)
                    .put("old_value", before).put("new_value", required).put("old_raw", rawBefore)
                    .put("changed", changed).put("realm_mode", s.mode().value())
                    .put("timestamp", Instant.now(clock).toString()).build());
            return new ClientRequirementChange(clientId, required, before, changed, s.mode().value(),
                    s.appliesTo(newRaw));
        });
    }

    /** A delegated admin may turn TOTP ON for their own app, never OFF: 403 otherwise. */
    public static void refuseDelegatedChange(boolean required) {
        MfaPolicy.delegatedChangeRefusal(required).ifPresent(m -> {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN, m);
        });
    }

    // ---- exemptions ------------------------------------------------------------------------

    public List<Exemption> listExemptions() {
        return executors.serviceAccount().call(realm -> {
            List<Exemption> out = new ArrayList<>();
            for (UserRepresentation u : directory.realmRoleUsers(realm, MfaConstants.EXEMPT_ROLE)) {
                UserRepresentation full = u.getAttributes() == null || u.getAttributes().isEmpty()
                        ? directory.getUser(realm, u.getId()).orElse(u) : u;
                out.add(new Exemption(u.getId(), u.getUsername(),
                        KeycloakDirectory.firstAttribute(full, MfaConstants.EXEMPT_REASON_ATTRIBUTE),
                        KeycloakDirectory.firstAttribute(full, MfaConstants.EXEMPT_GRANTED_BY_ATTRIBUTE),
                        KeycloakDirectory.firstAttribute(full, MfaConstants.EXEMPT_GRANTED_AT_ATTRIBUTE)));
            }
            return out;
        });
    }

    /**
     * {@code POST /realm/mfa/exemptions}: 400 without a 15–500 character justification; 409 when the
     * {@code mfa-exempt} role does not exist; 404 for no user. Writes the justification attributes
     * (declared admin-only in the user profile), CLEARS a pending CONFIGURE_TOTP, then grants the role.
     */
    public Exemption grantExemption(String userId, String reason, String actor, String requestIp) {
        String text;
        try {
            text = MfaPolicy.validateReason(reason);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, e.getMessage());
        }
        return executors.serviceAccount().call(realm -> {
            RoleRepresentation role = exemptRole(realm);
            UserRepresentation user = directory.getUser(realm, userId)
                    .orElseThrow(() -> ApiException.notFound("User not found"));
            ensureExemptionAttributesAdminOnly(realm);
            String ts = Instant.now(clock).toString();
            Map<String, List<String>> attrs = new HashMap<>(user.getAttributes() == null ? Map.of() : user.getAttributes());
            attrs.put(MfaConstants.EXEMPT_REASON_ATTRIBUTE, List.of(text));
            attrs.put(MfaConstants.EXEMPT_GRANTED_BY_ATTRIBUTE, List.of(actor));
            attrs.put(MfaConstants.EXEMPT_GRANTED_AT_ATTRIBUTE, List.of(ts));
            user.setAttributes(attrs);
            List<String> pending = user.getRequiredActions() == null ? List.of() : user.getRequiredActions();
            boolean clearedPending = pending.contains(MfaConstants.CONFIGURE_TOTP);
            user.setRequiredActions(pending.stream().filter(a -> !MfaConstants.CONFIGURE_TOTP.equals(a)).toList());
            // Justification first, role second: a failure between leaves a reason without an exemption.
            realm.users().get(userId).update(user);
            realm.users().get(userId).roles().realmLevel().add(List.of(role));
            audit.emit(AuditEvent.of(AuditEventType.MFA_EXEMPT_GRANTED).actor(actor).requestIp(requestIp)
                    .targetUser(userId).institution(KeycloakDirectory.firstAttribute(user, "institution"))
                    .put("target_username", user.getUsername()).put("reason", text).put("timestamp", ts)
                    .put("cleared_pending_configure_totp", clearedPending).build());
            return new Exemption(userId, user.getUsername(), text, actor, ts);
        });
    }

    /** {@code DELETE /realm/mfa/exemptions/{userId}}: remove the role and the justification attributes. */
    public Map<String, Object> revokeExemption(String userId, String actor, String requestIp) {
        return executors.serviceAccount().call(realm -> {
            RoleRepresentation role = exemptRole(realm);
            UserRepresentation user = directory.getUser(realm, userId)
                    .orElseThrow(() -> ApiException.notFound("User not found"));
            realm.users().get(userId).roles().realmLevel().remove(List.of(role));
            String previousReason = KeycloakDirectory.firstAttribute(user, MfaConstants.EXEMPT_REASON_ATTRIBUTE);
            Map<String, List<String>> attrs = new HashMap<>(user.getAttributes() == null ? Map.of() : user.getAttributes());
            MfaConstants.EXEMPT_ATTRIBUTES.forEach(attrs::remove);
            user.setAttributes(attrs);
            realm.users().get(userId).update(user);
            audit.emit(AuditEvent.of(AuditEventType.MFA_EXEMPT_REVOKED).actor(actor).requestIp(requestIp)
                    .targetUser(userId).institution(KeycloakDirectory.firstAttribute(user, "institution"))
                    .put("target_username", user.getUsername()).put("previous_reason", previousReason)
                    .put("timestamp", Instant.now(clock).toString()).build());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("user_id", userId);
            out.put("username", user.getUsername());
            out.put("revoked", true);
            return out;
        });
    }

    /**
     * Declare the exemption attributes in the user profile, admin-only (view+edit), so the exempt person
     * cannot rewrite their own justification in the account console. Idempotent.
     */
    public void ensureExemptionAttributesAdminOnly(RealmResource realm) {
        UPConfig config = realm.users().userProfile().getConfiguration();
        List<UPAttribute> attributes = config.getAttributes() == null ? new ArrayList<>()
                : new ArrayList<>(config.getAttributes());
        Set<String> present = new java.util.HashSet<>();
        attributes.forEach(a -> present.add(a.getName()));
        boolean added = false;
        for (String name : MfaConstants.EXEMPT_ATTRIBUTES) {
            if (present.contains(name)) {
                continue;
            }
            UPAttribute a = new UPAttribute(name);
            a.setDisplayName(name);
            a.setPermissions(new UPAttributePermissions(Set.of("admin"), Set.of("admin")));
            a.setMultivalued(false);
            attributes.add(a);
            added = true;
        }
        if (added) {
            config.setAttributes(attributes);
            realm.users().userProfile().update(config);
        }
    }

    /** Guard for every generic role-assignment path: {@code mfa-exempt} is not assignable there (400). */
    public static void refuseExemptRole(Collection<String> roleNames, String where) {
        if (MfaPolicy.namesExemptRole(roleNames)) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "'" + MfaConstants.EXEMPT_ROLE
                    + "' cannot be assigned through " + where + ". A super admin grants it with "
                    + "POST /realm/mfa/exemptions, which records the required justification.");
        }
    }

    // ---- the password-grant door -----------------------------------------------------------

    /** Does TOTP apply to logins to this client right now (mode + client attribute + flow bound)? */
    public boolean totpApplies(String clientId) {
        return currentState().appliesTo(clientValueCached(clientId).orElse(null));
    }

    /** TOTP applies to {@code clientId} AND the person (by realm roles) is not {@code mfa-exempt}. */
    public boolean totpRequiredFor(String clientId, Collection<String> realmRoles) {
        return totpApplies(clientId) && !MfaPolicy.isExempt(realmRoles);
    }

    /**
     * Why a password-grant token must not leave for {@code clientId} (empty = it may), from the claims of
     * the token Keycloak just issued (exemption and {@code acr} are read from it). FAILS OPEN when the
     * switch cannot be read.
     */
    public Optional<String> passwordGrantRefusal(Map<String, ?> claims, String clientId) {
        boolean enforced;
        try {
            enforced = totpApplies(clientId);
        } catch (RuntimeException e) {
            log.warn("MFA switch unreadable, password grant not gated: {}", e.getMessage());
            return Optional.empty();
        }
        return MfaPolicy.passwordGrantRefusal(claims, enforced);
    }

    /** The door: audit {@code auth.mfa.password_grant_refused} and throw 403 {@code mfa_required}. */
    public void enforcePasswordGrant(Map<String, ?> claims, String username, String clientId, String requestIp,
                                     String door) {
        Optional<String> reason = passwordGrantRefusal(claims, clientId);
        if (reason.isEmpty()) {
            return;
        }
        audit.emit(AuditEvent.of(AuditEventType.MFA_PASSWORD_GRANT_REFUSED).denied().actor(username)
                .clientId(clientId).requestIp(requestIp).put("door", door)
                .put("acr", claims == null ? null : claims.get("acr")).build());
        throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.MFA_REQUIRED, reason.get());
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Drop the cached switch and client values (after any write). */
    public void invalidate() {
        state = null;
        clientValues.clear();
    }

    private Optional<String> clientValueCached(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        Cached<Optional<String>> hit = clientValues.get(clientId);
        if (hit != null && hit.at().plus(STATE_TTL).isAfter(clock.instant())) {
            return hit.value();
        }
        Optional<String> value = executors.serviceAccount()
                .call(realm -> directory.findClient(realm, clientId).flatMap(MfaService::clientValue));
        clientValues.put(clientId, new Cached<>(value, clock.instant()));
        return value;
    }

    static Optional<String> clientValue(ClientRepresentation c) {
        return c.getAttributes() == null ? Optional.empty()
                : MfaPolicy.attribute(c.getAttributes(), MfaConstants.CLIENT_ATTRIBUTE);
    }

    static SwitchState switchState(RealmRepresentation rep) {
        Map<String, Object> realm = new HashMap<>();
        realm.put("browserFlow", rep.getBrowserFlow());
        realm.put("attributes", rep.getAttributes() == null ? Map.of() : rep.getAttributes());
        return SwitchState.fromRealm(realm);
    }

    private RoleRepresentation exemptRole(RealmResource realm) {
        return directory.realmRole(realm, MfaConstants.EXEMPT_ROLE).orElseThrow(() -> ApiException.conflict(
                ErrorCodes.CONFLICT, "Realm role '" + MfaConstants.EXEMPT_ROLE + "' does not exist. Apply "
                        + "infra/terraform with enable_mfa_totp = true (or the MFA flow installer).", Map.of()));
    }

    private static String attr(RealmRepresentation rep, String name) {
        return rep.getAttributes() == null ? null : rep.getAttributes().get(name);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
