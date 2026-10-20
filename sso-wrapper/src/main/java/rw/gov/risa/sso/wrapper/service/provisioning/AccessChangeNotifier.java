package rw.gov.risa.sso.wrapper.service.provisioning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.service.mail.DeliveryResult;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;

/**
 * Telling a person that an administrator changed which applications they can use (port of
 * {@code services/access_notifications.py}). The ONE place every door that changes one person's access to
 * one client hands its changes to — the access matrix (and its spreadsheet import), the delegated
 * revocation flow ({@code POST /institution-admin/access/revoke}) and the platform user page — so they
 * cannot drift apart on what a person is told.
 *
 * <p><b>Usage — collect, then flush once per request:</b>
 * <pre>{@code
 * AccessChangeNotifier.Batch batch = notifier.begin(AccessChangeNotifier.Kind.REVOKE, ctx.institution());
 * for (each APPLIED (user, client) change) batch.add(Recipient.of(userRep), clientId, Mailer.appLabel(clientId, name));
 * Map<String, String> notified = batch.flush();   // userId -> sent | failed: <why> | not_configured | no_address | disabled
 * Map<String, Integer> counts = AccessChangeNotifier.summarize(notified);   // for the response
 * }</pre>
 *
 * Rules, all deliberate:
 * <ul>
 *   <li><b>Existing accounts only.</b> A created account's email is provisioning's (it carries the
 *       set-password link) — never add a just-created account to a batch.</li>
 *   <li><b>One email per person per request.</b> Five applications granted to one person is one message
 *       listing five. A batch is one direction ({@link Kind}); a request that both grants and revokes uses
 *       two batches.</li>
 *   <li><b>Only what actually changed.</b> Add only APPLIED pairs — never a preview, a rejection, a skip or
 *       a failure. On a preview (dry run) simply never call {@link Batch#flush()}.</li>
 *   <li><b>{@code NOTIFY_ACCESS_CHANGES=false}</b> sends nothing (every person reports {@code disabled}).</li>
 *   <li><b>Never throws into the caller</b> and never changes the outcome of the change it reports.</li>
 * </ul>
 */
@Service(AccessChangeNotifier.BEAN)
public class AccessChangeNotifier {

    /** Explicit bean name: other packages define classes of the same simple name. */
    public static final String BEAN = "provisioningAccessChangeNotifier";

    private static final Logger log = LoggerFactory.getLogger(AccessChangeNotifier.class);

    public static final String SENT = "sent";
    public static final String FAILED = "failed";
    public static final String NOT_CONFIGURED = "not_configured";
    public static final String NO_ADDRESS = "no_address";
    public static final String DISABLED = "disabled";

    /** Which way the applications changed. */
    public enum Kind {
        GRANT("grant"),
        REVOKE("revoke");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** The person to tell (from the member listing the caller already holds — no extra Keycloak read). */
    public record Recipient(String userId, String email, String username, String name) {

        public static Recipient of(UserRepresentation u) {
            return new Recipient(u.getId(), u.getEmail(), u.getUsername(),
                    Mailer.personName(u.getFirstName(), u.getLastName(), u.getUsername()));
        }
    }

    private final Mailer mailer;

    public AccessChangeNotifier(Mailer mailer) {
        this.mailer = mailer;
    }

    /** A new collection for one request and one direction. */
    public Batch begin(Kind kind, String institution) {
        return new Batch(kind, institution);
    }

    /** Counts by status ({@code failed: …} folded into {@code failed}), for a report. */
    public static Map<String, Integer> summarize(Map<String, String> statuses) {
        Map<String, Integer> out = new LinkedHashMap<>();
        statuses.values().forEach(v -> {
            String key = v == null ? "" : v.split(":", 2)[0];
            out.merge(key, 1, Integer::sum);
        });
        return out;
    }

    /** One request's changes, grouped per person. Not thread-safe (one request = one thread). */
    public final class Batch {

        private final Kind kind;
        private final String institution;
        private final Map<String, Recipient> people = new LinkedHashMap<>();
        private final Map<String, List<String>> applications = new LinkedHashMap<>();
        private boolean flushed;

        private Batch(Kind kind, String institution) {
            this.kind = kind;
            this.institution = institution;
        }

        public Kind kind() {
            return kind;
        }

        /** Record one APPLIED change. {@code label} defaults to the client id; repeats are collapsed. */
        public Batch add(Recipient who, String clientId, String label) {
            if (who == null || who.userId() == null || flushed) {
                return this;
            }
            people.putIfAbsent(who.userId(), who);
            List<String> apps = applications.computeIfAbsent(who.userId(), k -> new ArrayList<>());
            String l = label == null || label.isBlank() ? clientId : label;
            if (l != null && !apps.contains(l)) {
                apps.add(l);
            }
            return this;
        }

        /** People collected so far. */
        public int size() {
            return people.size();
        }

        /**
         * Send one email per person. {@code userId -> sent | failed: <why> | not_configured | no_address |
         * disabled}. Idempotent: a second flush sends nothing and returns an empty map.
         */
        public Map<String, String> flush() {
            Map<String, String> out = new LinkedHashMap<>();
            if (flushed) {
                return out;
            }
            flushed = true;
            if (people.isEmpty()) {
                return out;
            }
            if (!mailer.settings().notifyAccessChanges()) {
                people.keySet().forEach(id -> out.put(id, DISABLED));
                return out;
            }
            if (!mailer.configured()) {
                people.keySet().forEach(id -> out.put(id, NOT_CONFIGURED));
                return out;
            }
            for (Recipient r : people.values()) {
                String to = r.email() == null ? "" : r.email().strip();
                if (to.isEmpty()) {
                    out.put(r.userId(), NO_ADDRESS);
                    continue;
                }
                try {
                    Mailer.AccessChangeMail mail = new Mailer.AccessChangeMail(to, r.name(), r.username(),
                            List.copyOf(applications.get(r.userId())), institution, null);
                    DeliveryResult d = kind == Kind.GRANT ? mailer.sendAccessGranted(mail)
                            : mailer.sendAccessRevoked(mail);
                    out.put(r.userId(), d.report());
                } catch (RuntimeException e) {
                    log.warn("access notification to {} could not be composed: {}", r.userId(), e.getMessage());
                    out.put(r.userId(), FAILED + ": " + e.getClass().getSimpleName());
                }
            }
            return out;
        }
    }
}
