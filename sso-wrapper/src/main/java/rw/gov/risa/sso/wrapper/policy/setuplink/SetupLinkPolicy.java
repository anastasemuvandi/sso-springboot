package rw.gov.risa.sso.wrapper.policy.setuplink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resending and honouring the set-password link — the rules, with no Keycloak and no HTTP. Port of
 * {@code app/core/setup_link.py}.
 *
 * <p>The link is the wrapper's own ({@code /account/setup?token=...}): a random token stored only as
 * its SHA-256, bound to the applications the person was given. ONE live link per account (issuing
 * a new one kills the old), 72 h lifespan, honoured only while {@code UPDATE_PASSWORD} is pending,
 * not spent by opening (mail scanners pre-fetch), spent by a successful set.
 *
 * <p>No enumeration: the public answer is {@link #PUBLIC_MESSAGE} whatever happened.
 */
public final class SetupLinkPolicy {

    public static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

    // Outcomes. Only SENT means an email went out.
    public static final String SENT = "sent";
    public static final String NO_ACCOUNT = "no_account";
    public static final String NOT_PENDING = "password_already_set";
    public static final String DISABLED = "account_disabled";
    public static final String NO_EMAIL = "no_email";
    public static final String NO_PASSWORD = "no_system_password";
    public static final String COOLDOWN = "cooldown";
    public static final String DAILY_LIMIT = "daily_limit";
    public static final String SEND_FAILED = "send_failed";
    public static final String INVALID_IDENTIFIER = "invalid_identifier";
    public static final String MAIL_NOT_CONFIGURED = "not_configured";
    public static final String LAST_EMAIL_DELIVERED = "last_email_delivered";

    // Delivery statuses and who sent.
    public static final String DELIVERY_SENT = "sent";
    public static final String DELIVERY_FAILED = "failed";
    public static final String DELIVERY_NOT_CONFIGURED = "not_configured";
    public static final List<String> DELIVERY_STATUSES = List.of(DELIVERY_SENT, DELIVERY_FAILED, DELIVERY_NOT_CONFIGURED);
    public static final String VIA_PROVISIONING = "provisioning";
    public static final String VIA_HOLDER = "holder_resend";
    public static final String VIA_ADMIN = "admin_resend";
    /** Statuses after which an admin may send one. */
    public static final Set<String> ADMIN_RESEND_AFTER = Set.of(DELIVERY_FAILED, DELIVERY_NOT_CONFIGURED);

    public static final Duration COOLDOWN_DURATION = Duration.ofMinutes(5);
    public static final int DAILY_LIMIT_COUNT = 5;
    public static final Duration LINK_LIFESPAN = Duration.ofHours(72);
    /** How long bound clients and delivery records are kept. */
    public static final Duration CLIENTS_TTL = Duration.ofDays(30);

    public static final int MAX_IDENTIFIER_LENGTH = 254;
    public static final int MAX_PASSWORD_LENGTH = 256;
    public static final int MAX_TOKEN_LENGTH = 200;
    public static final int TOKEN_BYTES = 32;

    // Why a link cannot be used.
    public static final String LINK_UNKNOWN = "link_unknown";
    public static final String LINK_SPENT = "password_already_set";
    public static final String PASSWORDS_DIFFER = "passwords_differ";
    public static final String PASSWORD_EMPTY = "password_empty";
    public static final String PASSWORD_REJECTED = "password_rejected";
    public static final String COMPLETED = "completed";

    public static final String INVALID_LINK_MESSAGE =
            "This link can no longer be used. It may already have been used to set a "
                    + "password, been replaced by a newer link, or expired. If you have not set "
                    + "your password yet, ask for a new link; if you have, sign in.";

    public static final String PUBLIC_MESSAGE =
            "If that address belongs to an account that is still waiting for its first "
                    + "password, a new link to set it has been sent. Check your inbox and spam "
                    + "folder; the link is valid for 72 hours. Already set your password? Use "
                    + "“Forgot password?” on the sign-in page instead.";

    public static final String DEFAULT_PASSWORD_ERROR =
            "That password does not meet the password rules. Please choose another.";

    public static final Map<String, String> ADMIN_RESEND_MESSAGES = Map.of(
            NO_ACCOUNT, "This employee has no SSO account.",
            DISABLED, "The account is disabled.",
            NO_EMAIL, "The account has no email address to send to.",
            NOT_PENDING, "The account's password has already been set. They can use “Forgot password?”.",
            NO_PASSWORD, "The account was not created with a system password, so there is nothing to set.",
            LAST_EMAIL_DELIVERED,
            "No failed set-password email is recorded for this account, so it is the employee's "
                    + "to ask for again: “Didn't get the email to set your password?” on the sign-in page.");

    private SetupLinkPolicy() {
    }

    /** The identifier as typed -> the stored form (trimmed, lowercased), or null when it cannot be one. */
    public static String normalise(String identifier) {
        String value = identifier == null ? "" : identifier.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > MAX_IDENTIFIER_LENGTH) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return null;
            }
        }
        return value;
    }

    private static boolean hasPassword(Collection<String> credentialTypes) {
        if (credentialTypes == null) {
            return false;
        }
        return credentialTypes.stream().anyMatch(t -> t != null && t.toLowerCase(Locale.ROOT).equals("password"));
    }

    private static boolean hasEmail(SetupAccount user) {
        return user.email() != null && !user.email().strip().isEmpty();
    }

    /**
     * Whether this account may be sent a set-password link (before rate limits).
     *
     * @param credentialTypes the account's credential {@code type}s
     */
    public static Decision eligibility(SetupAccount user, Collection<String> credentialTypes) {
        if (user == null) {
            return new Decision(NO_ACCOUNT);
        }
        if (!user.enabled()) {
            return new Decision(DISABLED);
        }
        if (!hasEmail(user)) {
            return new Decision(NO_EMAIL);
        }
        if (!user.requiredActions().contains(UPDATE_PASSWORD)) {
            return new Decision(NOT_PENDING);
        }
        if (!hasPassword(credentialTypes)) {
            return new Decision(NO_PASSWORD);
        }
        return new Decision(SENT);
    }

    /**
     * A provisioning report's email field ({@code sent}, {@code failed: <why>}, {@code not_configured},
     * ...) -> one of {@link #DELIVERY_STATUSES}, or null when no set-password email was meant to go out.
     */
    public static String deliveryStatus(String reportValue) {
        String v = reportValue == null ? "" : reportValue;
        int i = v.indexOf(':');
        String head = (i >= 0 ? v.substring(0, i) : v).strip();
        return DELIVERY_STATUSES.contains(head) ? head : null;
    }

    /** The account has not had its first password chosen yet. */
    public static boolean awaitingPassword(SetupAccount user) {
        return user != null && user.requiredActions().contains(UPDATE_PASSWORD);
    }

    /**
     * Why an admin may NOT send this account a link, or empty. The holder's rules first, then the
     * last email must not have gone out. {@code credentialTypes == null} skips the credential check
     * (the matrix's per-row hint, which has none to hand).
     */
    public static Optional<String> adminResendRefusal(
            SetupAccount user, Delivery lastDelivery, Collection<String> credentialTypes) {
        Decision d = eligibility(user, credentialTypes != null ? credentialTypes : List.of("password"));
        if (!d.send()) {
            return Optional.of(d.outcome());
        }
        String status = lastDelivery == null ? null : lastDelivery.status();
        if (status == null || !ADMIN_RESEND_AFTER.contains(status)) {
            return Optional.of(LAST_EMAIL_DELIVERED);
        }
        return Optional.empty();
    }

    /** {@code daily_limit} / {@code cooldown} when a send must wait, else empty. */
    public static Optional<String> throttle(Instant lastSentAt, int sentToday, Instant now) {
        if (sentToday >= DAILY_LIMIT_COUNT) {
            return Optional.of(DAILY_LIMIT);
        }
        if (lastSentAt != null && Duration.between(lastSentAt, now).compareTo(COOLDOWN_DURATION) < 0) {
            return Optional.of(COOLDOWN);
        }
        return Optional.empty();
    }

    /**
     * {@code candidate} when it points at one of {@code allowedOrigins}, else null (no open redirect).
     * A path ({@code /realms/...}) is resolved against the first allowed origin; {@code //host} is
     * not a path and is refused.
     */
    public static String safeReturnUrl(String candidate, Collection<String> allowedOrigins) {
        if (candidate == null || candidate.isEmpty()) {
            return null;
        }
        List<String> origins = new ArrayList<>();
        for (String o : allowedOrigins) {
            if (o != null && !o.isEmpty()) {
                origins.add(rstripSlash(o));
            }
        }
        if (candidate.startsWith("/") && !candidate.startsWith("//") && !origins.isEmpty()) {
            candidate = origins.get(0) + candidate;
        }
        UrlParts parts = UrlParts.split(candidate);
        if (!("http".equals(parts.scheme()) || "https".equals(parts.scheme())) || parts.netloc().isEmpty()) {
            return null;
        }
        String origin = (parts.scheme() + "://" + parts.netloc()).toLowerCase(Locale.ROOT);
        for (String o : origins) {
            if (o.toLowerCase(Locale.ROOT).equals(origin)) {
                return candidate;
            }
        }
        return null;
    }

    /** A new raw token: 32 random bytes, URL-safe base64 without padding (Python token_urlsafe). */
    public static String newToken(TokenSource source) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(source.nextBytes(TOKEN_BYTES));
    }

    /** What is stored and looked up: sha256 hex of the stripped token; null for blank or > 200 chars. */
    public static String tokenHash(String token) {
        String value = token == null ? "" : token.strip();
        if (value.isEmpty() || value.length() > MAX_TOKEN_LENGTH) {
            return null;
        }
        return sha256Hex(value);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Why a presented link must not be honoured, or empty. {@code record} is what the token's hash
     * looks up to; {@code currentHash} is the one live link for that account (a replaced link is as
     * unknown as one never issued).
     */
    public static Optional<String> linkRefusal(LinkRecord record, String currentHash, SetupAccount user, Instant now) {
        if (record == null || currentHash == null || currentHash.isEmpty() || !currentHash.equals(record.hash())) {
            return Optional.of(LINK_UNKNOWN);
        }
        if (record.expiresAt() == null || !record.expiresAt().isAfter(now)) {
            return Optional.of(LINK_UNKNOWN);
        }
        if (user == null || !user.enabled()) {
            return Optional.of(LINK_UNKNOWN);
        }
        if (!user.requiredActions().contains(UPDATE_PASSWORD)) {
            return Optional.of(LINK_SPENT);
        }
        return Optional.empty();
    }

    /** The checks made before Keycloak's own password policy is asked. */
    public static Optional<String> passwordRefusal(String password, String confirm) {
        if (password == null || password.isEmpty() || password.length() > MAX_PASSWORD_LENGTH) {
            return Optional.of(PASSWORD_EMPTY);
        }
        if (!password.equals(confirm)) {
            return Optional.of(PASSWORDS_DIFFER);
        }
        return Optional.empty();
    }

    /**
     * Where "open this application" goes: the client's home URL, by Keycloak's own resolution —
     * {@code baseUrl}, relative to {@code rootUrl} when it is a path; {@code ${authBaseUrl}} /
     * {@code ${authAdminUrl}} expand to Keycloak's public URL. Null when no http(s) address.
     */
    public static String launchUrl(String rootUrl, String baseUrl, String keycloakPublicUrl) {
        String kc = rstripSlash(keycloakPublicUrl == null ? "" : keycloakPublicUrl);
        String root = expand(rootUrl, kc);
        String base = expand(baseUrl, kc);
        String url;
        if (base.startsWith("http://") || base.startsWith("https://")) {
            url = base;
        } else if (base.startsWith("/") && !root.isEmpty()) {
            url = rstripSlash(root) + base;
        } else {
            url = root;
        }
        if (url.isEmpty()) {
            return null;
        }
        UrlParts parts = UrlParts.split(url);
        return ("http".equals(parts.scheme()) || "https".equals(parts.scheme())) && !parts.netloc().isEmpty()
                ? url : null;
    }

    private static String expand(String value, String kc) {
        String v = value == null ? "" : value.strip();
        return v.replace("${authBaseUrl}", kc).replace("${authAdminUrl}", kc);
    }

    /**
     * Keycloak's refusal of a password (its parsed JSON error body, or null) as something to show the
     * person: {@code error_description} or {@code errorMessage}, without the "Invalid password:"
     * prefix, first letter capitalised; a default sentence when there is none.
     */
    public static String passwordErrorText(Map<String, ?> body) {
        String text = "";
        if (body != null) {
            Object v = body.get("error_description");
            if (v == null || String.valueOf(v).isEmpty()) {
                v = body.get("errorMessage");
            }
            text = v == null ? "" : String.valueOf(v);
        }
        if (text.startsWith("Invalid password:")) {
            text = text.substring("Invalid password:".length());
        }
        text = text.strip();
        if (text.isEmpty()) {
            return DEFAULT_PASSWORD_ERROR;
        }
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    /** The account's required actions with {@code UPDATE_PASSWORD} removed (what a completed set writes). */
    public static List<String> remainingRequiredActions(SetupAccount user) {
        return user.requiredActions().stream().filter(a -> !UPDATE_PASSWORD.equals(a)).toList();
    }

    private static String rstripSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    /** Minimal {@code urllib.parse.urlsplit}: scheme and netloc only. */
    record UrlParts(String scheme, String netloc) {
        static UrlParts split(String url) {
            String scheme = "";
            String rest = url;
            int colon = url.indexOf(':');
            if (colon > 0 && isScheme(url.substring(0, colon))) {
                scheme = url.substring(0, colon).toLowerCase(Locale.ROOT);
                rest = url.substring(colon + 1);
            }
            String netloc = "";
            if (rest.startsWith("//")) {
                int end = rest.length();
                for (char c : new char[] {'/', '?', '#'}) {
                    int i = rest.indexOf(c, 2);
                    if (i >= 0 && i < end) {
                        end = i;
                    }
                }
                netloc = rest.substring(2, end);
            }
            return new UrlParts(scheme, netloc);
        }

        private static boolean isScheme(String s) {
            if (!Character.isLetter(s.charAt(0))) {
                return false;
            }
            for (char c : s.toCharArray()) {
                if (!(Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.')) {
                    return false;
                }
            }
            return true;
        }
    }
}
