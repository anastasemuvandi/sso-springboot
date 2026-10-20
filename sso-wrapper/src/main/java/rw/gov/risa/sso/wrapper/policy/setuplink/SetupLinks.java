package rw.gov.risa.sso.wrapper.policy.setuplink;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * The set-password link as a state machine over a {@link SetupLinkStore}. Port of the store half of
 * {@code SetupLinkService}; Keycloak reads/writes and mail stay in the service.
 *
 * <ul>
 *   <li>{@link #issue}: a new link; the previous live one is deleted (one live link per account);</li>
 *   <li>{@link #lookup} + {@link #check}: GET only checks — never spends;</li>
 *   <li>{@link #completionRefusal}: link + password checks before Keycloak's policy is asked;</li>
 *   <li>{@link #spend}: after Keycloak accepted the password (a refused password does NOT spend);</li>
 *   <li>a link is dead once {@code UPDATE_PASSWORD} is no longer pending, by any route.</li>
 * </ul>
 */
public final class SetupLinks {

    private final SetupLinkStore store;
    private final TokenSource tokens;
    private final Clock clock;

    public SetupLinks(SetupLinkStore store, TokenSource tokens, Clock clock) {
        this.store = store;
        this.tokens = tokens;
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    /** Add applications to what the person's link offers (order kept, de-duplicated, blanks dropped). */
    public List<String> bindClients(String userId, Collection<String> clientIds) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(store.boundClients(userId));
        for (String c : clientIds) {
            if (c != null && !c.isEmpty()) {
                merged.add(c);
            }
        }
        List<String> out = new ArrayList<>(merged);
        store.setBoundClients(userId, out, SetupLinkPolicy.CLIENTS_TTL);
        return out;
    }

    /** A new link for {@code userId}; the previous one, if any, stops working at once. */
    public IssuedLink issue(String userId, Collection<String> clientIds) {
        List<String> clients = bindClients(userId, clientIds);
        String token = SetupLinkPolicy.newToken(tokens);
        String digest = SetupLinkPolicy.tokenHash(token);
        store.currentHash(userId).ifPresent(store::deleteRecord);
        LinkRecord record = new LinkRecord(userId, digest, clients, now().plus(SetupLinkPolicy.LINK_LIFESPAN));
        store.saveRecord(record, SetupLinkPolicy.LINK_LIFESPAN);
        store.setCurrentHash(userId, digest, SetupLinkPolicy.LINK_LIFESPAN);
        return new IssuedLink(token, record);
    }

    /** Resolve a presented token. Never spends. */
    public LinkLookup lookup(String token) {
        String digest = SetupLinkPolicy.tokenHash(token);
        if (digest == null) {
            return LinkLookup.UNKNOWN;
        }
        Optional<LinkRecord> record = store.findRecord(digest);
        if (record.isEmpty()) {
            return LinkLookup.UNKNOWN;
        }
        return new LinkLookup(record.get(), store.currentHash(record.get().userId()).orElse(null));
    }

    /**
     * GET {@code /account/setup}: why the link cannot be used, or empty. {@code user} is the account
     * for {@link LinkLookup#userId()} (null when deleted/unknown).
     */
    public Optional<String> check(LinkLookup lookup, SetupAccount user) {
        return SetupLinkPolicy.linkRefusal(lookup.record(), lookup.currentHash(), user, now());
    }

    /**
     * POST {@code /account/setup}, before asking Keycloak: the link refusal first, then the password
     * checks. Empty = go ahead and set the password; on success call {@link #spend}.
     */
    public Optional<String> completionRefusal(LinkLookup lookup, SetupAccount user, String password, String confirm) {
        Optional<String> refusal = check(lookup, user);
        if (refusal.isPresent()) {
            return refusal;
        }
        return SetupLinkPolicy.passwordRefusal(password, confirm);
    }

    /** The password was set: delete the token record, the live hash and the delivery record. */
    public void spend(LinkRecord record) {
        store.deleteRecord(record.hash());
        store.deleteCurrentHash(record.userId());
        store.deleteDelivery(record.userId());
    }

    /** Kill the live link for {@code userId} (e.g. the account was disabled). */
    public void revoke(String userId) {
        store.currentHash(userId).ifPresent(store::deleteRecord);
        store.deleteCurrentHash(userId);
    }

    /** Remember how the last set-password email went (TTL 30 days). */
    public Delivery recordDelivery(String userId, String status, String via) {
        Delivery d = new Delivery(status, via, DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now().atOffset(ZoneOffset.UTC)));
        store.saveDelivery(userId, d, SetupLinkPolicy.CLIENTS_TTL);
        return d;
    }

    public Optional<Delivery> lastDelivery(String userId) {
        return store.lastDelivery(userId);
    }

    /** The holder-resend throttle for {@code userId} now: {@code daily_limit}, {@code cooldown}, or empty. */
    public Optional<String> throttle(String userId) {
        Instant now = now();
        return SetupLinkPolicy.throttle(store.lastSentAt(userId).orElse(null), store.sentOn(userId, today(now)), now);
    }

    /** Count one successful holder resend against the throttle. */
    public void recordSent(String userId) {
        Instant now = now();
        store.recordSent(userId, today(now), now, SetupLinkPolicy.COOLDOWN_DURATION);
    }

    private static LocalDate today(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
