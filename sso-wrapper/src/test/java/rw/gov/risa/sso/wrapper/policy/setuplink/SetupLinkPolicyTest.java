package rw.gov.risa.sso.wrapper.policy.setuplink;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SetupLinkPolicyTest {

    static SetupAccount waiting() {
        return new SetupAccount("u1", "nadia@a.gov.rw", "nadia@a.gov.rw", true, List.of("UPDATE_PASSWORD"));
    }

    static final List<String> PASSWORD = List.of("password");

    @Nested
    class Eligibility {
        @Test
        void a_waiting_account_with_a_system_password_is_sent_one() {
            Decision d = SetupLinkPolicy.eligibility(waiting(), PASSWORD);
            assertThat(d.outcome()).isEqualTo("sent");
            assertThat(d.send()).isTrue();
        }

        @Test
        void refusals_in_order() {
            assertThat(SetupLinkPolicy.eligibility(null, PASSWORD).outcome()).isEqualTo("no_account");
            assertThat(SetupLinkPolicy.eligibility(
                    new SetupAccount("u", "x", "x@y", false, List.of("UPDATE_PASSWORD")), PASSWORD).outcome())
                    .isEqualTo("account_disabled");
            assertThat(SetupLinkPolicy.eligibility(
                    new SetupAccount("u", "x", "  ", true, List.of("UPDATE_PASSWORD")), PASSWORD).outcome())
                    .isEqualTo("no_email");
            assertThat(SetupLinkPolicy.eligibility(new SetupAccount("u", "x", "x@y", true, List.of()), PASSWORD).outcome())
                    .isEqualTo("password_already_set");
            assertThat(SetupLinkPolicy.eligibility(waiting(), List.of("otp")).outcome()).isEqualTo("no_system_password");
            assertThat(SetupLinkPolicy.eligibility(waiting(), null).outcome()).isEqualTo("no_system_password");
            assertThat(SetupLinkPolicy.eligibility(waiting(), List.of("PASSWORD")).send()).isTrue();
            // Disabled beats every other refusal.
            assertThat(SetupLinkPolicy.eligibility(new SetupAccount("u", "x", null, false, List.of()), List.of()).outcome())
                    .isEqualTo("account_disabled");
        }
    }

    @Nested
    class Identifier {
        @Test
        void normalised_lowercase_trimmed() {
            assertThat(SetupLinkPolicy.normalise("  Nadia@A.gov.RW ")).isEqualTo("nadia@a.gov.rw");
        }

        @Test
        void unusable_identifiers() {
            assertThat(SetupLinkPolicy.normalise(null)).isNull();
            assertThat(SetupLinkPolicy.normalise("   ")).isNull();
            assertThat(SetupLinkPolicy.normalise("a b@c")).isNull();
            assertThat(SetupLinkPolicy.normalise("a".repeat(255))).isNull();
            assertThat(SetupLinkPolicy.normalise("a".repeat(254))).hasSize(254);
        }
    }

    @Nested
    class Delivery_ {
        @Test
        void report_values() {
            assertThat(SetupLinkPolicy.deliveryStatus("sent")).isEqualTo("sent");
            assertThat(SetupLinkPolicy.deliveryStatus("failed: relay said no")).isEqualTo("failed");
            assertThat(SetupLinkPolicy.deliveryStatus("not_configured")).isEqualTo("not_configured");
            assertThat(SetupLinkPolicy.deliveryStatus("not_needed")).isNull();
            assertThat(SetupLinkPolicy.deliveryStatus("skipped_shared_password")).isNull();
            assertThat(SetupLinkPolicy.deliveryStatus(null)).isNull();
        }

        @Test
        void awaiting_password() {
            assertThat(SetupLinkPolicy.awaitingPassword(waiting())).isTrue();
            assertThat(SetupLinkPolicy.awaitingPassword(new SetupAccount("u", "x", null, true, List.of()))).isFalse();
            assertThat(SetupLinkPolicy.awaitingPassword(null)).isFalse();
        }
    }

    @Nested
    class AdminResend {
        @ParameterizedTest
        @ValueSource(strings = {"failed", "not_configured"})
        void allowed_only_after_a_failed_or_unsent_email(String status) {
            assertThat(SetupLinkPolicy.adminResendRefusal(waiting(), new Delivery(status, "provisioning", "t"), PASSWORD))
                    .isEmpty();
        }

        @Test
        void a_delivered_or_unrecorded_email_is_the_holders_to_resend() {
            assertThat(SetupLinkPolicy.adminResendRefusal(waiting(), new Delivery("sent", "provisioning", "t"), PASSWORD))
                    .contains("last_email_delivered");
            assertThat(SetupLinkPolicy.adminResendRefusal(waiting(), null, PASSWORD)).contains("last_email_delivered");
        }

        @Test
        void the_holders_rules_come_first() {
            assertThat(SetupLinkPolicy.adminResendRefusal(null, new Delivery("failed", "x", "t"), PASSWORD))
                    .contains("no_account");
            assertThat(SetupLinkPolicy.adminResendRefusal(waiting(), new Delivery("failed", "x", "t"), List.of()))
                    .contains("no_system_password");
            // No credentials to hand (the matrix's per-row hint): the credential check is skipped.
            assertThat(SetupLinkPolicy.adminResendRefusal(waiting(), new Delivery("failed", "x", "t"), null)).isEmpty();
        }

        @Test
        void every_refusal_has_a_message() {
            for (String code : List.of("no_account", "account_disabled", "no_email", "password_already_set",
                    "no_system_password", "last_email_delivered")) {
                assertThat(SetupLinkPolicy.ADMIN_RESEND_MESSAGES).containsKey(code);
            }
        }
    }

    @Nested
    class Throttle {
        final Instant now = Instant.parse("2026-10-20T10:00:00Z");

        @Test
        void cooldown_and_daily_limit() {
            assertThat(SetupLinkPolicy.throttle(null, 0, now)).isEmpty();
            assertThat(SetupLinkPolicy.throttle(now.minusSeconds(299), 1, now)).contains("cooldown");
            assertThat(SetupLinkPolicy.throttle(now.minusSeconds(300), 1, now)).isEmpty();
            assertThat(SetupLinkPolicy.throttle(null, 5, now)).contains("daily_limit");
            assertThat(SetupLinkPolicy.throttle(now.minusSeconds(1), 5, now)).contains("daily_limit");
            assertThat(SetupLinkPolicy.throttle(null, 4, now)).isEmpty();
        }
    }

    @Nested
    class ReturnUrl {
        final List<String> allowed = List.of("https://sso.gov.rw/");

        @Test
        void same_origin_absolute_and_paths() {
            assertThat(SetupLinkPolicy.safeReturnUrl("https://sso.gov.rw/realms/x/login", allowed))
                    .isEqualTo("https://sso.gov.rw/realms/x/login");
            assertThat(SetupLinkPolicy.safeReturnUrl("HTTPS://SSO.gov.rw/a", allowed)).isEqualTo("HTTPS://SSO.gov.rw/a");
            assertThat(SetupLinkPolicy.safeReturnUrl("/realms/x/login-actions/restart", allowed))
                    .isEqualTo("https://sso.gov.rw/realms/x/login-actions/restart");
        }

        @Test
        void open_redirects_refused() {
            assertThat(SetupLinkPolicy.safeReturnUrl("https://evil.example/", allowed)).isNull();
            assertThat(SetupLinkPolicy.safeReturnUrl("//evil.example/x", allowed)).isNull();
            assertThat(SetupLinkPolicy.safeReturnUrl("javascript:alert(1)", allowed)).isNull();
            assertThat(SetupLinkPolicy.safeReturnUrl("https://sso.gov.rw.evil.example/", allowed)).isNull();
            assertThat(SetupLinkPolicy.safeReturnUrl("", allowed)).isNull();
            assertThat(SetupLinkPolicy.safeReturnUrl(null, allowed)).isNull();
            assertThat(SetupLinkPolicy.safeReturnUrl("/realms/x", List.of())).isNull();
        }
    }

    @Nested
    class Tokens {
        @Test
        void token_is_urlsafe_base64_of_32_bytes() {
            String t = SetupLinkPolicy.newToken(TokenSource.secure());
            assertThat(t).hasSize(43).matches("[A-Za-z0-9_-]+");
            assertThat(SetupLinkPolicy.newToken(n -> new byte[n])).isEqualTo("A".repeat(43));
        }

        @Test
        void only_the_sha256_is_stored() {
            assertThat(SetupLinkPolicy.tokenHash("abc"))
                    .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            assertThat(SetupLinkPolicy.tokenHash(" abc ")).isEqualTo(SetupLinkPolicy.tokenHash("abc"));
            assertThat(SetupLinkPolicy.tokenHash("")).isNull();
            assertThat(SetupLinkPolicy.tokenHash(null)).isNull();
            assertThat(SetupLinkPolicy.tokenHash("x".repeat(201))).isNull();
            assertThat(SetupLinkPolicy.tokenHash("x".repeat(200))).isNotNull();
        }
    }

    @Nested
    class LinkRefusal {
        final Instant now = Instant.parse("2026-10-20T10:00:00Z");
        final LinkRecord record = new LinkRecord("u1", "h1", List.of(), now.plusSeconds(60));

        @Test
        void live_link_is_honoured() {
            assertThat(SetupLinkPolicy.linkRefusal(record, "h1", waiting(), now)).isEmpty();
        }

        @Test
        void unknown_replaced_expired_disabled_or_deleted() {
            assertThat(SetupLinkPolicy.linkRefusal(null, "h1", waiting(), now)).contains("link_unknown");
            assertThat(SetupLinkPolicy.linkRefusal(record, null, waiting(), now)).contains("link_unknown");
            assertThat(SetupLinkPolicy.linkRefusal(record, "h2", waiting(), now)).contains("link_unknown"); // replaced
            assertThat(SetupLinkPolicy.linkRefusal(record, "h1", waiting(), now.plusSeconds(60))).contains("link_unknown");
            assertThat(SetupLinkPolicy.linkRefusal(record, "h1", null, now)).contains("link_unknown");
            assertThat(SetupLinkPolicy.linkRefusal(record, "h1",
                    new SetupAccount("u1", "x", "x@y", false, List.of("UPDATE_PASSWORD")), now)).contains("link_unknown");
        }

        @Test
        void dead_once_the_password_was_set_by_any_route() {
            assertThat(SetupLinkPolicy.linkRefusal(record, "h1", new SetupAccount("u1", "x", "x@y", true, List.of()), now))
                    .contains("password_already_set");
        }
    }

    @Nested
    class Passwords {
        @Test
        void checks_before_keycloak() {
            assertThat(SetupLinkPolicy.passwordRefusal("", "")).contains("password_empty");
            assertThat(SetupLinkPolicy.passwordRefusal(null, null)).contains("password_empty");
            assertThat(SetupLinkPolicy.passwordRefusal("x".repeat(257), "x".repeat(257))).contains("password_empty");
            assertThat(SetupLinkPolicy.passwordRefusal("Abcdefgh12", "Abcdefgh13")).contains("passwords_differ");
            assertThat(SetupLinkPolicy.passwordRefusal("Abcdefgh12", "Abcdefgh12")).isEmpty();
        }

        @Test
        void keycloak_error_text() {
            assertThat(SetupLinkPolicy.passwordErrorText(Map.of("error_description", "Invalid password: must contain 1 digit")))
                    .isEqualTo("Must contain 1 digit");
            assertThat(SetupLinkPolicy.passwordErrorText(Map.of("errorMessage", "password history")))
                    .isEqualTo("Password history");
            assertThat(SetupLinkPolicy.passwordErrorText(Map.of())).isEqualTo(SetupLinkPolicy.DEFAULT_PASSWORD_ERROR);
            assertThat(SetupLinkPolicy.passwordErrorText(null)).isEqualTo(SetupLinkPolicy.DEFAULT_PASSWORD_ERROR);
            assertThat(SetupLinkPolicy.passwordErrorText(Map.of("error_description", "Invalid password:  ")))
                    .isEqualTo(SetupLinkPolicy.DEFAULT_PASSWORD_ERROR);
        }

        @Test
        void remaining_required_actions() {
            SetupAccount u = new SetupAccount("u", "x", null, true, List.of("VERIFY_EMAIL", "UPDATE_PASSWORD", "CONFIGURE_TOTP"));
            assertThat(SetupLinkPolicy.remainingRequiredActions(u)).containsExactly("VERIFY_EMAIL", "CONFIGURE_TOTP");
        }
    }

    @Nested
    class LaunchUrl {
        final String kc = "https://sso.gov.rw/";

        @Test
        void base_url_absolute_relative_or_root() {
            assertThat(SetupLinkPolicy.launchUrl("https://app.gov.rw", "https://other.gov.rw/home", kc))
                    .isEqualTo("https://other.gov.rw/home");
            assertThat(SetupLinkPolicy.launchUrl("https://app.gov.rw/", "/home", kc)).isEqualTo("https://app.gov.rw/home");
            assertThat(SetupLinkPolicy.launchUrl("https://app.gov.rw", null, kc)).isEqualTo("https://app.gov.rw");
            assertThat(SetupLinkPolicy.launchUrl(null, "/home", kc)).isNull();
            assertThat(SetupLinkPolicy.launchUrl("${authBaseUrl}", "/realms/x/account/", kc))
                    .isEqualTo("https://sso.gov.rw/realms/x/account/");
            assertThat(SetupLinkPolicy.launchUrl("ftp://files", null, kc)).isNull();
            assertThat(SetupLinkPolicy.launchUrl("", "", kc)).isNull();
        }
    }
}
