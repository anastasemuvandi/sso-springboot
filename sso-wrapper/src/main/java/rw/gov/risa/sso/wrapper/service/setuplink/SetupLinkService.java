package rw.gov.risa.sso.wrapper.service.setuplink;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.setuplink.Delivery;
import rw.gov.risa.sso.wrapper.policy.setuplink.IssuedLink;
import rw.gov.risa.sso.wrapper.policy.setuplink.LinkLookup;
import rw.gov.risa.sso.wrapper.policy.setuplink.SetupAccount;
import rw.gov.risa.sso.wrapper.policy.setuplink.SetupLinkPolicy;
import rw.gov.risa.sso.wrapper.policy.setuplink.SetupLinks;
import rw.gov.risa.sso.wrapper.policy.setuplink.TokenSource;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.service.mail.DeliveryResult;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;

/**
 * The wrapper's own set-password link (spec §5.12; port of {@code setup_link_service.py}). Rules and
 * the state machine are {@code policy.setuplink} ({@link SetupLinks}); this class holds the Redis store,
 * the Keycloak writes (service account), the mail and the audit.
 *
 * <p>One live link per account, only {@code sha256(token)} stored, 72 h, spent on a successful set, dead
 * once {@code UPDATE_PASSWORD} is no longer pending; {@link #open} (GET) never spends; a password the
 * realm policy refuses does not spend. Controllers for {@code /account/setup*} live elsewhere.
 */
@Service
public class SetupLinkService {

    private static final Logger log = LoggerFactory.getLogger(SetupLinkService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 400 error code when Keycloak's password policy refuses the password. */
    public static final String PASSWORD_REJECTED = SetupLinkPolicy.PASSWORD_REJECTED;
    /** Admin resends allowed per caller per minute. */
    public static final int ADMIN_RESEND_PER_MINUTE = 10;

    private final KeycloakExecutors executors;
    private final KeycloakDirectory directory;
    private final SetupLinks links;
    private final Mailer mailer;
    private final ClientAccessService access;
    private final SsoProperties sso;
    private final AuditEmitter audit;
    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public SetupLinkService(KeycloakExecutors executors, KeycloakDirectory directory, RedisSetupLinkStore store,
                            Mailer mailer, ClientAccessService access, SsoProperties sso, AuditEmitter audit,
                            StringRedisTemplate redis) {
        this(executors, directory, new SetupLinks(store, TokenSource.secure(), Clock.systemUTC()), mailer, access, sso,
                audit, redis, Clock.systemUTC());
    }

    public SetupLinkService(KeycloakExecutors executors, KeycloakDirectory directory, SetupLinks links, Mailer mailer,
                            ClientAccessService access, SsoProperties sso, AuditEmitter audit,
                            StringRedisTemplate redis, Clock clock) {
        this.executors = executors;
        this.directory = directory;
        this.links = links;
        this.mailer = mailer;
        this.access = access;
        this.sso = sso;
        this.audit = audit;
        this.redis = redis;
        this.clock = clock;
    }

    // ---- results ---------------------------------------------------------------------------

    /** GET {@code /account/setup}: {@code refusal} null = the link may be used. */
    public record LinkCheck(String refusal, UserRepresentation user) {
        public boolean usable() {
            return refusal == null;
        }
    }

    /** An application the person can open now. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AppLink(String clientId, String name, String url) {
    }

    /**
     * POST {@code /account/setup}: {@code outcome} = {@code completed} | a link refusal | {@code password_empty}
     * | {@code passwords_differ} | {@code password_rejected} (with Keycloak's {@code message}). Only
     * {@code completed} spends the link.
     */
    public record Completion(String outcome, String message, UserRepresentation user, List<AppLink> applications) {
        public boolean completed() {
            return SetupLinkPolicy.COMPLETED.equals(outcome);
        }
    }

    /** An admin resend: {@code outcome} plus the human message for refusals. */
    public record AdminResend(String outcome, String message, UserRepresentation user) {
        public boolean sent() {
            return SetupLinkPolicy.SENT.equals(outcome);
        }
    }

    // ---- the store -------------------------------------------------------------------------

    /** The policy state machine over the Redis store (for features that need lower-level access). */
    public SetupLinks links() {
        return links;
    }

    /** {@code <API_BASE_URL>/account/setup?token=<token>}. */
    public String setupUrl(String token) {
        String base = sso.apiBaseUrl() == null ? "" : sso.apiBaseUrl().replaceAll("/+$", "");
        return base + "/account/setup?token=" + token;
    }

    /** Issue a link (the previous live one dies); returns the raw token + record. Mail it; never log it. */
    public IssuedLink issue(String userId, Collection<String> clientIds) {
        return links.issue(userId, clientIds == null ? List.of() : clientIds);
    }

    /** {@link #issue} → the URL to put in the email. */
    public String issueUrl(String userId, Collection<String> clientIds) {
        return setupUrl(issue(userId, clientIds).token());
    }

    /** Add applications to what the person's link offers after the password is set. */
    public List<String> bindClients(String userId, Collection<String> clientIds) {
        return links.bindClients(userId, clientIds);
    }

    /** Kill the live link (e.g. the account was disabled). */
    public void revoke(String userId) {
        links.revoke(userId);
    }

    /** Record how the last set-password email went ({@code sent|failed|not_configured}, via). */
    public Delivery recordDelivery(String userId, String status, String via) {
        return links.recordDelivery(userId, status, via);
    }

    public Optional<Delivery> lastDelivery(String userId) {
        return links.lastDelivery(userId);
    }

    // ---- using the link --------------------------------------------------------------------

    /** GET: why the link cannot be used, or null. Never spends. */
    public LinkCheck open(String token) {
        LinkLookup lookup = links.lookup(token);
        UserRepresentation user = loadUser(lookup.userId());
        return new LinkCheck(links.check(lookup, account(user)).orElse(null), user);
    }

    /** POST: set the password through the link, once. */
    public Completion complete(String token, String password, String confirm, String requestIp) {
        LinkLookup lookup = links.lookup(token);
        UserRepresentation user = loadUser(lookup.userId());
        Optional<String> refusal = links.check(lookup, account(user));
        if (refusal.isPresent()) {
            auditUse(refusal.get(), user, requestIp);
            return new Completion(refusal.get(), null, user, List.of());
        }
        Optional<String> bad = SetupLinkPolicy.passwordRefusal(password, confirm);
        if (bad.isPresent()) {
            return new Completion(bad.get(), null, user, List.of());
        }
        try {
            setPassword(user.getId(), password);
        } catch (ApiException e) {
            if (PASSWORD_REJECTED.equals(e.error())) {
                return new Completion(PASSWORD_REJECTED, e.getMessage(), user, List.of());
            }
            throw e;
        }
        links.spend(lookup.record());
        auditUse(SetupLinkPolicy.COMPLETED, user, requestIp);
        return new Completion(SetupLinkPolicy.COMPLETED, null, user, applications(user, lookup.record().clientIds()));
    }

    /**
     * Set a permanent password (reset-password, {@code temporary=false}), then drop {@code UPDATE_PASSWORD}
     * and mark the email verified. Keycloak's password-policy refusal → 400 {@code password_rejected}
     * with Keycloak's message (nothing else changed).
     */
    public void setPassword(String userId, String password) {
        executors.serviceAccount().run(realm -> {
            CredentialRepresentation cred = new CredentialRepresentation();
            cred.setType(CredentialRepresentation.PASSWORD);
            cred.setValue(password);
            cred.setTemporary(false);
            try {
                realm.users().get(userId).resetPassword(cred);
            } catch (BadRequestException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, PASSWORD_REJECTED,
                        SetupLinkPolicy.passwordErrorText(errorBody(e)));
            }
            UserRepresentation fresh = realm.users().get(userId).toRepresentation();
            fresh.setRequiredActions(SetupLinkPolicy.remainingRequiredActions(account(fresh)));
            fresh.setEmailVerified(true);
            realm.users().get(userId).update(fresh);
        });
    }

    /**
     * The bound applications the person can open NOW (each re-checked by the access rule, so one revoked
     * since the link was issued is not offered), with the client's home URL.
     */
    public List<AppLink> applications(UserRepresentation user, Collection<String> clientIds) {
        List<AppLink> out = new ArrayList<>();
        if (user == null || clientIds == null || clientIds.isEmpty()) {
            return out;
        }
        Map<String, ClientRepresentation> byId = new LinkedHashMap<>();
        try {
            executors.serviceAccount().call(directory::listClients).forEach(c -> byId.put(c.getClientId(), c));
        } catch (RuntimeException e) {
            log.warn("setup-link: could not list clients: {}", e.getMessage());
            return out;
        }
        for (String clientId : new LinkedHashSet<>(clientIds)) {
            ClientRepresentation rep = byId.get(clientId);
            if (rep == null || Boolean.FALSE.equals(rep.isEnabled())) {
                continue;
            }
            try {
                if (!access.checkLogin(user.getUsername(), clientId).hasAccess()) {
                    continue;
                }
            } catch (RuntimeException e) {
                log.warn("setup-link: access check failed for {}: {}", clientId, e.getMessage());
                continue;
            }
            String name = rep.getName() != null && !rep.getName().isBlank() && !rep.getName().startsWith("${")
                    ? rep.getName() : clientId;
            out.add(new AppLink(clientId, name,
                    SetupLinkPolicy.launchUrl(rep.getRootUrl(), rep.getBaseUrl(), sso.keycloak().publicUrl())));
        }
        return out;
    }

    // ---- resend by the holder --------------------------------------------------------------

    /**
     * "Didn't get the email…": returns the REAL outcome for audit/tests; the page must show
     * {@link SetupLinkPolicy#PUBLIC_MESSAGE} whatever it is. Never throws. Throttled per account
     * (cooldown + daily limit); the route also carries an IP rate limit.
     */
    public String requestResend(String identifier, String requestIp) {
        String normalised = SetupLinkPolicy.normalise(identifier);
        UserRepresentation user = null;
        String outcome;
        try {
            if (normalised == null) {
                outcome = SetupLinkPolicy.INVALID_IDENTIFIER;
            } else {
                Decided decided = decideAndSend(normalised);
                outcome = decided.outcome();
                user = decided.user();
            }
        } catch (RuntimeException e) {
            log.error("setup-link resend failed: {}", e.getMessage());
            outcome = SetupLinkPolicy.SEND_FAILED;
        }
        audit.emit(AuditEvent.of(AuditEventType.SETUP_LINK_RESEND)
                .outcome(SetupLinkPolicy.SENT.equals(outcome) ? "success" : "failure")
                .actor(user != null ? user.getUsername() : normalised)
                .requestIp(requestIp)
                .targetUser(user == null ? null : user.getId())
                .institution(user == null ? null : KeycloakDirectory.firstAttribute(user, "institution"))
                .put("result", outcome)
                .put("timestamp", Instant.now(clock).toString()).build());
        log.info("setup-link resend: {}", outcome);
        return outcome;
    }

    private record Decided(String outcome, UserRepresentation user) {
    }

    private Decided decideAndSend(String identifier) {
        UserRepresentation found = executors.serviceAccount().call(realm -> findOne(realm, identifier));
        if (found == null) {
            return new Decided(SetupLinkPolicy.NO_ACCOUNT, null);
        }
        UserRepresentation user = loadUser(found.getId());
        List<String> credentials = credentialTypes(found.getId());
        var decision = SetupLinkPolicy.eligibility(account(user), credentials);
        if (!decision.send()) {
            return new Decided(decision.outcome(), user);
        }
        Optional<String> held = links.throttle(user.getId());
        if (held.isPresent()) {
            return new Decided(held.get(), user);
        }
        String url = issueUrl(user.getId(), List.of());
        DeliveryResult r = mailer.sendSetupLink(setupMail(user, url), SetupLinkPolicy.VIA_HOLDER);
        if (!r.isSent()) {
            log.warn("setup-link resend: could not mail the account: {}", r.detail());
            return new Decided(SetupLinkPolicy.SEND_FAILED, user);
        }
        links.recordSent(user.getId());
        return new Decided(SetupLinkPolicy.SENT, user);
    }

    /** The ONE account whose username or email is exactly {@code identifier}; null when none or several. */
    private UserRepresentation findOne(RealmResource realm, String identifier) {
        Map<String, UserRepresentation> found = new LinkedHashMap<>();
        directory.findUserByUsername(realm, identifier).ifPresent(u -> found.put(u.getId(), u));
        for (UserRepresentation u : directory.findUsersByEmail(realm, identifier, 2)) {
            if (u.getId() != null) {
                found.put(u.getId(), u);
            }
        }
        return found.size() == 1 ? found.values().iterator().next() : null;
    }

    // ---- resend by the institution's admin -------------------------------------------------

    /**
     * {@code POST /institution-admin/access-matrix/setup-email/{n}} (the caller has checked
     * {@code manage-members} and that the account is its institution's): only when the last delivery was
     * {@code failed}/{@code not_configured}; 10/min per caller (429 {@code rate_limited}); audited
     * {@code admin.setup_link.resend}. With no relay nothing is issued (nobody could be told).
     */
    public AdminResend adminResend(String userId, String actor, String institution, String requestIp) {
        enforceAdminRate(actor);
        UserRepresentation user = loadUser(userId);
        List<String> credentials = user == null ? List.of() : credentialTypes(userId);
        Delivery last = links.lastDelivery(userId).orElse(null);
        String outcome = SetupLinkPolicy.adminResendRefusal(account(user), last, credentials).orElse(null);
        if (outcome == null) {
            if (!mailer.configured()) {
                outcome = SetupLinkPolicy.MAIL_NOT_CONFIGURED;
                links.recordDelivery(userId, SetupLinkPolicy.DELIVERY_NOT_CONFIGURED, SetupLinkPolicy.VIA_ADMIN);
            } else {
                String url = issueUrl(userId, List.of());
                DeliveryResult r = mailer.sendSetupLink(setupMail(user, url), SetupLinkPolicy.VIA_ADMIN);
                outcome = r.isSent() ? SetupLinkPolicy.SENT : SetupLinkPolicy.SEND_FAILED;
            }
        }
        audit.emit(AuditEvent.of(AuditEventType.SETUP_LINK_ADMIN_RESEND)
                .outcome(SetupLinkPolicy.SENT.equals(outcome) ? "success" : "failure")
                .actor(actor).requestIp(requestIp).institution(institution).targetUser(userId)
                .put("result", outcome)
                .put("target_username", user == null ? null : user.getUsername())
                .put("previous_delivery", last == null ? null : last.status())
                .put("timestamp", Instant.now(clock).toString()).build());
        return new AdminResend(outcome, SetupLinkPolicy.ADMIN_RESEND_MESSAGES.get(outcome), user);
    }

    /** {@code setup-link:admin-resend:<actor>:<epochMinute>} counter; fails open when Redis is down. */
    private void enforceAdminRate(String actor) {
        long minute = Instant.now(clock).getEpochSecond() / 60;
        String key = "setup-link:admin-resend:" + actor + ":" + minute;
        Long n;
        try {
            n = redis.opsForValue().increment(key);
            redis.expire(key, Duration.ofSeconds(90));
        } catch (RuntimeException e) {
            log.warn("setup-link: admin resend rate check unavailable: {}", e.getMessage());
            return;
        }
        if (n != null && n > ADMIN_RESEND_PER_MINUTE) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED,
                    "Too many setup-email resends; try again in a minute.");
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private Mailer.SetupLinkMail setupMail(UserRepresentation user, String url) {
        return new Mailer.SetupLinkMail(user.getId(), user.getEmail(),
                Mailer.personName(user.getFirstName(), user.getLastName(), user.getUsername()), user.getUsername(), url,
                (int) SetupLinkPolicy.LINK_LIFESPAN.toHours());
    }

    private UserRepresentation loadUser(String userId) {
        if (userId == null) {
            return null;
        }
        try {
            return executors.serviceAccount().call(realm -> directory.getUser(realm, userId).orElse(null));
        } catch (WebApplicationException e) {
            return null;
        }
    }

    private List<String> credentialTypes(String userId) {
        return executors.serviceAccount().call(realm -> directory.credentialTypes(realm, userId));
    }

    /** A Keycloak user as the setup-link rules see it (null for null). */
    public static SetupAccount account(UserRepresentation u) {
        if (u == null) {
            return null;
        }
        return new SetupAccount(u.getId(), u.getUsername(), u.getEmail(), !Boolean.FALSE.equals(u.isEnabled()),
                u.getRequiredActions(), u.getAttributes());
    }

    private void auditUse(String outcome, UserRepresentation user, String requestIp) {
        audit.emit(AuditEvent.of(AuditEventType.SETUP_LINK_USED)
                .outcome(SetupLinkPolicy.COMPLETED.equals(outcome) ? "success" : "failure")
                .actor(user == null ? null : user.getUsername())
                .requestIp(requestIp)
                .targetUser(user == null ? null : user.getId())
                .institution(user == null ? null : KeycloakDirectory.firstAttribute(user, "institution"))
                .put("result", outcome)
                .put("timestamp", Instant.now(clock).toString()).build());
    }

    private static Map<String, ?> errorBody(WebApplicationException e) {
        try {
            if (e.getResponse() != null && e.getResponse().hasEntity()) {
                return JSON.readValue(e.getResponse().readEntity(String.class), Map.class);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }
}
