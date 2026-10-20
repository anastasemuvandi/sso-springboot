package rw.gov.risa.sso.wrapper.policy.setuplink;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SetupLinksTest {

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-20T08:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    final MutableClock clock = new MutableClock();
    final InMemorySetupLinkStore store = new InMemorySetupLinkStore(clock);
    final AtomicInteger seq = new AtomicInteger();
    /** Deterministic, distinct tokens. */
    final TokenSource tokens = n -> {
        byte[] b = new byte[n];
        b[0] = (byte) seq.incrementAndGet();
        return b;
    };
    final SetupLinks links = new SetupLinks(store, tokens, clock);

    SetupAccount waiting = new SetupAccount("u1", "nadia@a.gov.rw", "nadia@a.gov.rw", true, List.of("UPDATE_PASSWORD"));

    @Test
    void issue_then_open_does_not_spend() {
        IssuedLink link = links.issue("u1", List.of("minagri-portal"));
        assertThat(link.record().hash()).isEqualTo(SetupLinkPolicy.tokenHash(link.token()));
        assertThat(link.record().expiresAt()).isEqualTo(clock.now.plus(Duration.ofHours(72)));
        assertThat(link.url("https://api.gov.rw/")).isEqualTo("https://api.gov.rw/account/setup?token=" + link.token());

        LinkLookup l = links.lookup(link.token());
        assertThat(l.userId()).isEqualTo("u1");
        assertThat(links.check(l, waiting)).isEmpty();
        // GET again: still fine — opening never spends.
        assertThat(links.check(links.lookup(link.token()), waiting)).isEmpty();
    }

    @Test
    void the_raw_token_is_never_stored() {
        IssuedLink link = links.issue("u1", List.of());
        assertThat(store.findRecord(link.token())).isEmpty();
        assertThat(store.findRecord(SetupLinkPolicy.tokenHash(link.token()))).isPresent();
    }

    @Test
    void one_live_link_per_account_a_new_one_kills_the_old() {
        IssuedLink first = links.issue("u1", List.of("a"));
        IssuedLink second = links.issue("u1", List.of("b"));
        assertThat(links.check(links.lookup(first.token()), waiting)).contains("link_unknown");
        assertThat(links.check(links.lookup(second.token()), waiting)).isEmpty();
        // Bound applications accumulate across links.
        assertThat(second.record().clientIds()).containsExactly("a", "b");
    }

    @Test
    void expires_after_72_hours() {
        IssuedLink link = links.issue("u1", List.of());
        clock.advance(Duration.ofHours(72).minusSeconds(1));
        assertThat(links.check(links.lookup(link.token()), waiting)).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        assertThat(links.check(links.lookup(link.token()), waiting)).contains("link_unknown");
    }

    @Test
    void spent_on_success_and_delivery_record_cleared() {
        IssuedLink link = links.issue("u1", List.of());
        links.recordDelivery("u1", "failed", "provisioning");
        LinkLookup l = links.lookup(link.token());
        assertThat(links.completionRefusal(l, waiting, "Abcdefgh12", "Abcdefgh12")).isEmpty();
        links.spend(l.record());
        assertThat(links.lookup(link.token())).isEqualTo(LinkLookup.UNKNOWN);
        assertThat(links.check(links.lookup(link.token()), waiting)).contains("link_unknown");
        assertThat(links.lastDelivery("u1")).isEmpty();
    }

    @Test
    void a_refused_password_does_not_spend() {
        IssuedLink link = links.issue("u1", List.of());
        LinkLookup l = links.lookup(link.token());
        assertThat(links.completionRefusal(l, waiting, "a", "b")).contains("passwords_differ");
        // Not spent: the person can try another.
        assertThat(links.completionRefusal(links.lookup(link.token()), waiting, "Abcdefgh12", "Abcdefgh12")).isEmpty();
    }

    @Test
    void dead_when_update_password_no_longer_pending() {
        IssuedLink link = links.issue("u1", List.of());
        SetupAccount done = new SetupAccount("u1", "x", "x@y", true, List.of());
        assertThat(links.check(links.lookup(link.token()), done)).contains("password_already_set");
        assertThat(links.completionRefusal(links.lookup(link.token()), done, "Abcdefgh12", "Abcdefgh12"))
                .contains("password_already_set");
    }

    @Test
    void the_link_refusal_comes_before_the_password_checks() {
        assertThat(links.completionRefusal(links.lookup("nope"), waiting, "", "")).contains("link_unknown");
    }

    @Test
    void unknown_and_malformed_tokens() {
        assertThat(links.lookup(null)).isEqualTo(LinkLookup.UNKNOWN);
        assertThat(links.lookup("x".repeat(500))).isEqualTo(LinkLookup.UNKNOWN);
        assertThat(links.check(links.lookup("never-issued"), waiting)).contains("link_unknown");
    }

    @Test
    void revoke_kills_the_live_link() {
        IssuedLink link = links.issue("u1", List.of());
        links.revoke("u1");
        assertThat(links.check(links.lookup(link.token()), waiting)).contains("link_unknown");
        assertThat(store.currentHash("u1")).isEmpty();
    }

    @Test
    void delivery_records() {
        assertThat(links.lastDelivery("u1")).isEmpty();
        Delivery d = links.recordDelivery("u1", "sent", "admin_resend");
        assertThat(d.at()).startsWith("2026-10-20T08:00");
        assertThat(links.lastDelivery("u1")).contains(d);
        clock.advance(Duration.ofDays(31));
        assertThat(links.lastDelivery("u1")).isEmpty();
    }

    @Test
    void holder_resend_throttle() {
        assertThat(links.throttle("u1")).isEmpty();
        links.recordSent("u1");
        assertThat(links.throttle("u1")).contains("cooldown");
        clock.advance(Duration.ofMinutes(5));
        assertThat(links.throttle("u1")).isEmpty();
        for (int i = 0; i < 4; i++) {
            links.recordSent("u1");
            clock.advance(Duration.ofMinutes(6));
        }
        assertThat(links.throttle("u1")).contains("daily_limit");
        clock.advance(Duration.ofDays(1));
        assertThat(links.throttle("u1")).isEmpty();
    }

    @Test
    void binding_dedupes_and_drops_blanks() {
        assertThat(links.bindClients("u1", List.of("a", "", "b"))).containsExactly("a", "b");
        assertThat(links.bindClients("u1", List.of("b", "c"))).containsExactly("a", "b", "c");
    }
}
