package rw.gov.risa.sso.wrapper.service.mfa;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import jakarta.ws.rs.WebApplicationException;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import rw.gov.risa.sso.mfa.policy.BulkSkipReason;
import rw.gov.risa.sso.mfa.policy.MfaConstants;
import rw.gov.risa.sso.mfa.policy.MfaPolicy;
import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;

/**
 * Bulk CONFIGURE_TOTP for every existing user without an OTP credential (port of
 * {@code mfa_bulk_enrol.py}). Started by {@link MfaService#setMode} the first time the mode becomes
 * {@code all}; can also be run directly.
 *
 * <ul>
 *   <li><b>Idempotent</b>: {@link MfaPolicy#bulkEnrolmentSkip} skips the enrolled, the already pending,
 *       disabled users, service accounts and EVERY {@code mfa-exempt} holder.</li>
 *   <li><b>Resumable</b>: progress is checkpointed to Redis {@code mfa:bulk-enrol:checkpoint} (JSON summary,
 *       TTL 30 d) after every page; a finished checkpoint restarts as a fresh (still idempotent) pass.</li>
 *   <li><b>Single flight</b> across instances: Redis lock {@code mfa:bulk-enrol:lock} (SET NX, 2 h lease).</li>
 * </ul>
 */
@Component
public class MfaBulkEnrolment {

    private static final Logger log = LoggerFactory.getLogger(MfaBulkEnrolment.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String CHECKPOINT_KEY = "mfa:bulk-enrol:checkpoint";
    public static final String LOCK_KEY = "mfa:bulk-enrol:lock";
    static final Duration LOCK_LEASE = Duration.ofHours(2);
    static final Duration CHECKPOINT_TTL = Duration.ofDays(30);
    public static final int PAGE_SIZE = 100;

    private final KeycloakExecutors executors;
    private final KeycloakDirectory directory;
    private final StringRedisTemplate redis;
    private final AuditEmitter audit;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mfa-bulk-enrol");
        t.setDaemon(true);
        return t;
    });

    public MfaBulkEnrolment(KeycloakExecutors executors, KeycloakDirectory directory, StringRedisTemplate redis,
                            AuditEmitter audit) {
        this.executors = executors;
        this.directory = directory;
        this.redis = redis;
        this.audit = audit;
    }

    /** Progress and outcome of a run (the checkpoint shape). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static final class Summary {
        public int examined;
        public int assigned;
        public Map<String, Integer> skipped = new LinkedHashMap<>();
        public List<Map<String, String>> errors = new ArrayList<>();
        public int nextOffset;
        public boolean finished;
        public boolean dryRun;

        void skip(String reason) {
            skipped.merge(reason, 1, Integer::sum);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("examined", examined);
            m.put("assigned", assigned);
            m.put("skipped", new LinkedHashMap<>(skipped));
            m.put("errors", new ArrayList<>(errors));
            m.put("next_offset", nextOffset);
            m.put("finished", finished);
            m.put("dry_run", dryRun);
            return m;
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    /**
     * Start a run in the background (single flight). Returns {@code {started, reason}}; the outcome is
     * audited {@code admin.mfa.bulk_enrol} with the summary.
     */
    public Map<String, Object> startAsync(String actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!tryLock()) {
            out.put("started", false);
            out.put("reason", "already running");
            return out;
        }
        worker.submit(() -> {
            String outcome;
            Map<String, Object> extra;
            try {
                Summary s = runLocked(false);
                outcome = s.errors.isEmpty() ? "success" : "failure";
                extra = s.asMap();
            } catch (RuntimeException e) {
                outcome = "failure";
                extra = Map.of("error", String.valueOf(e.getMessage()));
            } finally {
                unlock();
            }
            audit.emit(AuditEvent.of(AuditEventType.MFA_BULK_ENROL).outcome(outcome).actor(actor).putAll(extra).build());
        });
        out.put("started", true);
        out.put("reason", "first time the switch was turned on");
        return out;
    }

    /** Run synchronously (holds the lock while running; returns immediately "already running" if held). */
    public Summary run(boolean dryRun) {
        if (!tryLock()) {
            Summary busy = new Summary();
            busy.errors.add(Map.of("error", "already running"));
            return busy;
        }
        try {
            return runLocked(dryRun);
        } finally {
            unlock();
        }
    }

    Summary runLocked(boolean dryRun) {
        Summary summary = loadCheckpoint();
        summary.dryRun = dryRun;
        return executors.serviceAccount().call(realm -> {
            Set<String> exempt = new HashSet<>();
            directory.realmRoleUsers(realm, MfaConstants.EXEMPT_ROLE).forEach(u -> exempt.add(u.getId()));
            while (true) {
                List<UserRepresentation> page = directory.listUsers(realm, summary.nextOffset, PAGE_SIZE, true);
                if (page.isEmpty()) {
                    summary.finished = true;
                    saveCheckpoint(summary);
                    break;
                }
                for (UserRepresentation user : page) {
                    summary.examined++;
                    enrolOne(realm, user, exempt.contains(user.getId()), dryRun, summary);
                }
                summary.nextOffset += page.size();
                saveCheckpoint(summary);
                log.info("bulk CONFIGURE_TOTP: examined={} assigned={} skipped={} errors={}{}", summary.examined,
                        summary.assigned, summary.skipped, summary.errors.size(), dryRun ? " (dry run)" : "");
                if (page.size() < PAGE_SIZE) {
                    summary.finished = true;
                    saveCheckpoint(summary);
                    break;
                }
            }
            return summary;
        });
    }

    private void enrolOne(RealmResource realm, UserRepresentation user, boolean exempt, boolean dryRun, Summary s) {
        String uid = user.getId();
        try {
            boolean hasOtp = directory.credentialTypes(realm, uid).contains(MfaConstants.OTP_CREDENTIAL_TYPE);
            Optional<BulkSkipReason> skip = MfaPolicy.bulkEnrolmentSkip(!Boolean.FALSE.equals(user.isEnabled()),
                    user.getUsername(), false, // service accounts are recognised by their username prefix
                    user.getRequiredActions(), hasOtp, exempt);
            if (skip.isPresent()) {
                s.skip(skip.get().code());
                return;
            }
            if (!dryRun) {
                // Full representation back: Keycloak 26 validates a PUT against the whole user profile.
                UserRepresentation full = realm.users().get(uid).toRepresentation();
                List<String> actions = new ArrayList<>(full.getRequiredActions() == null ? List.of()
                        : full.getRequiredActions());
                if (!actions.contains(MfaConstants.CONFIGURE_TOTP)) {
                    actions.add(MfaConstants.CONFIGURE_TOTP);
                }
                full.setRequiredActions(actions);
                realm.users().get(uid).update(full);
            }
            s.assigned++;
        } catch (WebApplicationException e) {
            s.errors.add(Map.of("user_id", uid, "username", user.getUsername() == null ? "" : user.getUsername(),
                    "error", "HTTP " + (e.getResponse() == null ? "?" : e.getResponse().getStatus())));
        }
    }

    // ---- Redis -----------------------------------------------------------------------------

    Summary loadCheckpoint() {
        try {
            String raw = redis.opsForValue().get(CHECKPOINT_KEY);
            if (raw == null) {
                return new Summary();
            }
            Summary s = JSON.readValue(raw, Summary.class);
            return s.finished ? new Summary() : s;
        } catch (Exception e) {
            log.warn("bulk CONFIGURE_TOTP: checkpoint unreadable, starting fresh: {}", e.getMessage());
            return new Summary();
        }
    }

    void saveCheckpoint(Summary s) {
        try {
            redis.opsForValue().set(CHECKPOINT_KEY, JSON.writeValueAsString(s), CHECKPOINT_TTL);
        } catch (Exception e) {
            log.warn("bulk CONFIGURE_TOTP: could not save the checkpoint: {}", e.getMessage());
        }
    }

    /** The last checkpoint (progress of the current/last run), if any. */
    public Optional<Map<String, Object>> progress() {
        try {
            String raw = redis.opsForValue().get(CHECKPOINT_KEY);
            return raw == null ? Optional.empty() : Optional.of(JSON.readValue(raw, Summary.class).asMap());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private boolean tryLock() {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_LEASE));
        } catch (RuntimeException e) {
            log.warn("bulk CONFIGURE_TOTP: lock unavailable ({}); running without it", e.getMessage());
            return true;
        }
    }

    private void unlock() {
        try {
            redis.delete(LOCK_KEY);
        } catch (RuntimeException e) {
            // the lease expires on its own
        }
    }
}
