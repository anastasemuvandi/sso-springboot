package rw.gov.risa.sso.wrapper.service.platformops.realm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import jakarta.ws.rs.core.Response;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.info.ThemeInfoRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.service.platformops.PlatformOpsProperties;
import rw.gov.risa.sso.wrapper.service.platformops.realm.RealmDtos.BrandingConfig;
import rw.gov.risa.sso.wrapper.service.platformops.realm.RealmDtos.LogoUploaded;
import rw.gov.risa.sso.wrapper.service.platformops.realm.RealmDtos.RealmConfig;
import rw.gov.risa.sso.wrapper.service.platformops.realm.RealmDtos.SmtpConfig;
import rw.gov.risa.sso.wrapper.service.platformops.realm.RealmDtos.SmtpTestResult;
import rw.gov.risa.sso.wrapper.service.platformops.realm.RealmDtos.ThemesResponse;

/**
 * Realm configuration, branding/logo, themes and SMTP (port of {@code api/realm.py} +
 * {@code keycloak_admin_service} realm helpers), as the wrapper's service account. Every write is
 * audited ({@code admin.realm.updated}) with the section and the changed field names — never a secret.
 *
 * <p>The SMTP password is NEVER returned: Keycloak answers it masked and this service removes the key
 * altogether. A PUT without a password keeps the stored one (Keycloak's secret sentinel).
 */
@Service
public class RealmSettingsService {

    private static final Logger log = LoggerFactory.getLogger(RealmSettingsService.class);
    private static final ObjectMapper JSON = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** Keycloak's "keep the stored secret" value ({@code ComponentRepresentation.SECRET_VALUE}). */
    public static final String SECRET_SENTINEL = "**********";
    public static final List<String> BRANDING_ATTRIBUTES = List.of("logo_url", "primary_color", "secondary_color",
            "background_color", "favicon_url", "welcome_title", "welcome_message");
    public static final Set<String> LOGO_TYPES = Set.of("image/jpeg", "image/jpg", "image/png", "image/gif",
            "image/svg+xml");
    private static final Pattern SVG_ACTIVE = Pattern.compile(
            "(?is)<\\s*script|<\\s*foreignObject|\\son[a-z]+\\s*=|javascript:");

    private final KeycloakExecutors executors;
    private final KeycloakAdminFactory factory;
    private final AuditEmitter audit;
    private final PlatformOpsProperties props;

    public RealmSettingsService(KeycloakExecutors executors, KeycloakAdminFactory factory, AuditEmitter audit,
                                PlatformOpsProperties props) {
        this.executors = executors;
        this.factory = factory;
        this.audit = audit;
        this.props = props;
    }

    // ---- realm config ----------------------------------------------------------------------

    /** {@code GET /realm/config}: the realm representation, SMTP password removed. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> config() {
        RealmRepresentation rep = realm();
        Map<String, Object> out = JSON.convertValue(rep, Map.class);
        if (out.get("smtpServer") instanceof Map<?, ?> smtp) {
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) smtp);
            copy.remove("password");
            out.put("smtpServer", copy);
        }
        return out;
    }

    /** {@code PUT /realm/config}: only the fields sent are written. */
    public Map<String, Object> updateConfig(RealmConfig body, CallerPrincipal caller, String ip) {
        List<String> changed = new ArrayList<>();
        executors.serviceAccount().run(realm -> {
            RealmRepresentation rep = realm.toRepresentation();
            if (body.realm() != null && !body.realm().equals(rep.getRealm())) {
                throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                        "The realm cannot be renamed through this endpoint.");
            }
            set(changed, "displayName", body.displayName(), rep::getDisplayName, rep::setDisplayName);
            set(changed, "displayNameHtml", body.displayNameHtml(), rep::getDisplayNameHtml, rep::setDisplayNameHtml);
            set(changed, "enabled", body.enabled(), rep::isEnabled, rep::setEnabled);
            set(changed, "loginTheme", body.loginTheme(), rep::getLoginTheme, rep::setLoginTheme);
            set(changed, "adminTheme", body.adminTheme(), rep::getAdminTheme, rep::setAdminTheme);
            set(changed, "accountTheme", body.accountTheme(), rep::getAccountTheme, rep::setAccountTheme);
            set(changed, "emailTheme", body.emailTheme(), rep::getEmailTheme, rep::setEmailTheme);
            set(changed, "internationalizationEnabled", body.internationalizationEnabled(),
                    rep::isInternationalizationEnabled, rep::setInternationalizationEnabled);
            if (body.supportedLocales() != null
                    && !Objects.equals(new java.util.HashSet<>(body.supportedLocales()), rep.getSupportedLocales())) {
                rep.setSupportedLocales(new java.util.LinkedHashSet<>(body.supportedLocales()));
                changed.add("supportedLocales");
            }
            set(changed, "defaultLocale", body.defaultLocale(), rep::getDefaultLocale, rep::setDefaultLocale);
            set(changed, "registrationAllowed", body.registrationAllowed(), rep::isRegistrationAllowed,
                    rep::setRegistrationAllowed);
            set(changed, "registrationEmailAsUsername", body.registrationEmailAsUsername(),
                    rep::isRegistrationEmailAsUsername, rep::setRegistrationEmailAsUsername);
            set(changed, "rememberMe", body.rememberMe(), rep::isRememberMe, rep::setRememberMe);
            set(changed, "verifyEmail", body.verifyEmail(), rep::isVerifyEmail, rep::setVerifyEmail);
            set(changed, "loginWithEmailAllowed", body.loginWithEmailAllowed(), rep::isLoginWithEmailAllowed,
                    rep::setLoginWithEmailAllowed);
            set(changed, "duplicateEmailsAllowed", body.duplicateEmailsAllowed(), rep::isDuplicateEmailsAllowed,
                    rep::setDuplicateEmailsAllowed);
            set(changed, "resetPasswordAllowed", body.resetPasswordAllowed(), rep::isResetPasswordAllowed,
                    rep::setResetPasswordAllowed);
            set(changed, "editUsernameAllowed", body.editUsernameAllowed(), rep::isEditUsernameAllowed,
                    rep::setEditUsernameAllowed);
            set(changed, "bruteForceProtected", body.bruteForceProtected(), rep::isBruteForceProtected,
                    rep::setBruteForceProtected);
            set(changed, "permanentLockout", body.permanentLockout(), rep::isPermanentLockout,
                    rep::setPermanentLockout);
            set(changed, "maxFailureWaitSeconds", body.maxFailureWaitSeconds(), rep::getMaxFailureWaitSeconds,
                    rep::setMaxFailureWaitSeconds);
            set(changed, "minimumQuickLoginWaitSeconds", body.minimumQuickLoginWaitSeconds(),
                    rep::getMinimumQuickLoginWaitSeconds, rep::setMinimumQuickLoginWaitSeconds);
            set(changed, "waitIncrementSeconds", body.waitIncrementSeconds(), rep::getWaitIncrementSeconds,
                    rep::setWaitIncrementSeconds);
            set(changed, "quickLoginCheckMilliSeconds", body.quickLoginCheckMilliSeconds(),
                    rep::getQuickLoginCheckMilliSeconds, rep::setQuickLoginCheckMilliSeconds);
            set(changed, "maxDeltaTimeSeconds", body.maxDeltaTimeSeconds(), rep::getMaxDeltaTimeSeconds,
                    rep::setMaxDeltaTimeSeconds);
            set(changed, "failureFactor", body.failureFactor(), rep::getFailureFactor, rep::setFailureFactor);
            if (!changed.isEmpty()) {
                realm.update(rep);
            }
        });
        audited(caller, ip, "config", changed);
        return Map.of("message", "Realm configuration updated successfully", "changed", changed);
    }

    // ---- branding --------------------------------------------------------------------------

    /** {@code GET /realm/branding}: themes, display name and the branding attributes. */
    public Map<String, Object> branding() {
        RealmRepresentation rep = realm();
        Map<String, String> attrs = rep.getAttributes() == null ? Map.of() : rep.getAttributes();
        Map<String, Object> themes = new LinkedHashMap<>();
        themes.put("login", rep.getLoginTheme());
        themes.put("admin", rep.getAdminTheme());
        themes.put("account", rep.getAccountTheme());
        themes.put("email", rep.getEmailTheme());
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("name", rep.getDisplayName());
        display.put("html_name", rep.getDisplayNameHtml());
        Map<String, Object> custom = new LinkedHashMap<>();
        BRANDING_ATTRIBUTES.forEach(a -> custom.put(a, attrs.get(a)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("themes", themes);
        out.put("display", display);
        out.put("custom_attributes", custom);
        return out;
    }

    /** {@code PUT /realm/branding}: blank fields are left as they are (as the reference). */
    public Map<String, Object> updateBranding(BrandingConfig b, CallerPrincipal caller, String ip) {
        List<String> changed = new ArrayList<>();
        executors.serviceAccount().run(realm -> {
            RealmRepresentation rep = realm.toRepresentation();
            setIfGiven(changed, "loginTheme", b.loginTheme(), rep::getLoginTheme, rep::setLoginTheme);
            setIfGiven(changed, "adminTheme", b.adminTheme(), rep::getAdminTheme, rep::setAdminTheme);
            setIfGiven(changed, "accountTheme", b.accountTheme(), rep::getAccountTheme, rep::setAccountTheme);
            setIfGiven(changed, "emailTheme", b.emailTheme(), rep::getEmailTheme, rep::setEmailTheme);
            Map<String, String> attrs = new HashMap<>(rep.getAttributes() == null ? Map.of() : rep.getAttributes());
            Map<String, String> given = new LinkedHashMap<>();
            given.put("logo_url", b.logoUrl());
            given.put("primary_color", b.primaryColor());
            given.put("secondary_color", b.secondaryColor());
            given.put("background_color", b.backgroundColor());
            given.put("favicon_url", b.faviconUrl());
            given.put("welcome_title", b.welcomeTitle());
            given.put("welcome_message", b.welcomeMessage());
            given.forEach((k, v) -> {
                if (v != null && !v.isBlank() && !v.equals(attrs.get(k))) {
                    attrs.put(k, v);
                    changed.add(k);
                }
            });
            if (!changed.isEmpty()) {
                rep.setAttributes(attrs);
                realm.update(rep);
            }
        });
        audited(caller, ip, "branding", changed);
        return Map.of("message", "Branding configuration updated successfully", "changed", changed);
    }

    /**
     * {@code POST /realm/branding/logo}: JPEG/PNG/GIF/SVG up to {@code logoMaxBytes} (5 MB), whose bytes
     * really are that type (magic numbers; an SVG with script or event handlers is refused). Stored as a
     * {@code data:} URL in the realm attribute {@code logo_url}.
     */
    public LogoUploaded uploadLogo(String filename, String contentType, byte[] content, CallerPrincipal caller,
                                   String ip) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].strip();
        if (!LOGO_TYPES.contains(type)) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "Invalid file type. Allowed types: "
                    + String.join(", ", List.of("image/jpeg", "image/jpg", "image/png", "image/gif", "image/svg+xml")));
        }
        if (content == null || content.length == 0) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "The file is empty.");
        }
        if (content.length > props.logoMaxBytes()) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "File size too large. Maximum size is "
                    + (props.logoMaxBytes() / (1024 * 1024)) + "MB");
        }
        if (!contentMatches(type, content)) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                    "The file's content is not a valid " + type + " image.");
        }
        String dataUrl = "data:" + type + ";base64," + Base64.getEncoder().encodeToString(content);
        executors.serviceAccount().run(realm -> {
            RealmRepresentation rep = realm.toRepresentation();
            Map<String, String> attrs = new HashMap<>(rep.getAttributes() == null ? Map.of() : rep.getAttributes());
            attrs.put("logo_url", dataUrl);
            rep.setAttributes(attrs);
            realm.update(rep);
        });
        audit.emit(AuditEvent.of(AuditEventType.ADMIN_REALM_UPDATED).actor(caller.username()).requestIp(ip)
                .put("section", "logo").put("changed", List.of("logo_url")).put("filename", filename)
                .put("content_type", type).put("size", content.length).build());
        return new LogoUploaded("Logo uploaded successfully", dataUrl, filename, content.length);
    }

    /** True when the bytes are what the declared image type says. */
    static boolean contentMatches(String type, byte[] b) {
        return switch (type) {
            case "image/png" -> b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            case "image/jpeg", "image/jpg" -> b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8
                    && (b[2] & 0xFF) == 0xFF;
            case "image/gif" -> b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8';
            case "image/svg+xml" -> {
                String text = new String(b, StandardCharsets.UTF_8);
                yield text.toLowerCase(Locale.ROOT).contains("<svg") && !SVG_ACTIVE.matcher(text).find();
            }
            default -> false;
        };
    }

    // ---- themes ----------------------------------------------------------------------------

    /** {@code GET /realm/themes}: theme names per type from {@code /admin/serverinfo}. */
    public ThemesResponse themes() {
        Map<String, List<ThemeInfoRepresentation>> themes;
        try (Keycloak kc = factory.newServiceAccountClient()) {
            themes = kc.serverInfo().getInfo().getThemes();
        }
        return new ThemesResponse(names(themes, "login"), names(themes, "admin"), names(themes, "account"),
                names(themes, "email"));
    }

    private static List<String> names(Map<String, List<ThemeInfoRepresentation>> themes, String type) {
        List<ThemeInfoRepresentation> list = themes == null ? null : themes.get(type);
        return list == null ? List.of() : list.stream().map(ThemeInfoRepresentation::getName)
                .filter(Objects::nonNull).sorted().toList();
    }

    // ---- email -----------------------------------------------------------------------------

    /** {@code GET /realm/email/config}: Keycloak's {@code smtpServer} map WITHOUT the password. */
    public Map<String, Object> emailConfig() {
        RealmRepresentation rep = realm();
        Map<String, Object> out = new LinkedHashMap<>();
        if (rep.getSmtpServer() != null) {
            rep.getSmtpServer().forEach((k, v) -> {
                if (!"password".equals(k)) {
                    out.put(k, v);
                }
            });
        }
        return out;
    }

    /** {@code PUT /realm/email/config}: replaces {@code smtpServer}; no password = keep the stored one. */
    public Map<String, Object> updateEmailConfig(SmtpConfig c, CallerPrincipal caller, String ip) {
        executors.serviceAccount().run(realm -> {
            RealmRepresentation rep = realm.toRepresentation();
            Map<String, String> before = rep.getSmtpServer() == null ? Map.of() : rep.getSmtpServer();
            rep.setSmtpServer(smtpMap(c, before));
            realm.update(rep);
        });
        audit.emit(AuditEvent.of(AuditEventType.ADMIN_REALM_UPDATED).actor(caller.username()).requestIp(ip)
                .put("section", "email").put("host", c.host()).put("port", c.port() == null ? 25 : c.port())
                .put("password_changed", c.password() != null && !c.password().isBlank()).build());
        return Map.of("message", "Email configuration updated successfully");
    }

    /** The {@code smtpServer} map Keycloak stores (all strings), password kept unless a new one is given. */
    static Map<String, String> smtpMap(SmtpConfig c, Map<String, String> before) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("host", c.host().strip());
        m.put("port", String.valueOf(c.port() == null ? 25 : c.port()));
        m.put("from", c.from().strip());
        putIf(m, "fromDisplayName", c.fromDisplayName());
        putIf(m, "replyTo", c.replyTo());
        putIf(m, "replyToDisplayName", c.replyToDisplayName());
        putIf(m, "envelopeFrom", c.envelopeFrom());
        m.put("ssl", String.valueOf(Boolean.TRUE.equals(c.ssl())));
        m.put("starttls", String.valueOf(Boolean.TRUE.equals(c.starttls())));
        boolean auth = Boolean.TRUE.equals(c.auth());
        m.put("auth", String.valueOf(auth));
        if (auth) {
            putIf(m, "user", c.user());
            if (c.password() != null && !c.password().isBlank()) {
                m.put("password", c.password());
            } else if (before.get("password") != null && !before.get("password").isEmpty()) {
                m.put("password", SECRET_SENTINEL); // Keycloak keeps the stored secret
            }
        }
        return m;
    }

    /**
     * {@code POST /realm/email/test}: Keycloak's {@code testSMTPConnection} with the stored settings. Keycloak
     * sends the test mail to the AUTHENTICATED admin's address; the service account has none, so when
     * Keycloak says so the test is retried with the caller's own token. Never throws: the outcome is the body.
     */
    public SmtpTestResult testEmail(CallerPrincipal caller) {
        Map<String, String> settings;
        try {
            RealmRepresentation rep = realm();
            settings = rep.getSmtpServer() == null ? Map.of() : new LinkedHashMap<>(rep.getSmtpServer());
        } catch (RuntimeException e) {
            return new SmtpTestResult(false, "Failed to test email connection: " + e.getMessage());
        }
        if (settings.get("host") == null || settings.get("host").isBlank()) {
            return new SmtpTestResult(false, "SMTP is not configured: set the host first.");
        }
        SmtpTestResult first = testWith(() -> factory.asServiceAccount(r -> status(r.testSMTPConnection(settings))));
        if (!first.success() && first.message().toLowerCase(Locale.ROOT).contains("e-mail")
                && caller != null && caller.accessToken() != null) {
            return testWith(() -> factory.asCaller(caller.accessToken(), r -> status(r.testSMTPConnection(settings))));
        }
        return first;
    }

    private static SmtpTestResult testWith(Supplier<SmtpTestResult> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            return new SmtpTestResult(false, "Failed to test email connection: " + e.getMessage());
        }
    }

    private static SmtpTestResult status(Response r) {
        try (r) {
            if (r.getStatus() == 204 || r.getStatus() == 200) {
                return new SmtpTestResult(true, "SMTP connection successful");
            }
            String body = r.hasEntity() ? r.readEntity(String.class) : "";
            return new SmtpTestResult(false, "SMTP test failed: " + (body == null || body.isBlank()
                    ? "HTTP " + r.getStatus() : body));
        }
    }

    // ---- overview --------------------------------------------------------------------------

    /** {@code GET /realm/overview}: one summary of the realm's settings (no secret). */
    public Map<String, Object> overview() {
        RealmRepresentation rep = realm();
        Map<String, String> smtp = rep.getSmtpServer() == null ? Map.of() : rep.getSmtpServer();
        Map<String, String> attrs = rep.getAttributes() == null ? Map.of() : rep.getAttributes();
        ThemesResponse available;
        try {
            available = themes();
        } catch (RuntimeException e) {
            log.warn("realm overview: themes unavailable: {}", e.toString());
            available = new ThemesResponse(List.of(), List.of(), List.of(), List.of());
        }
        Map<String, Object> realmInfo = new LinkedHashMap<>();
        realmInfo.put("name", rep.getRealm());
        realmInfo.put("display_name", rep.getDisplayName());
        realmInfo.put("enabled", rep.isEnabled());
        realmInfo.put("registration_allowed", rep.isRegistrationAllowed());
        realmInfo.put("login_with_email", rep.isLoginWithEmailAllowed());
        realmInfo.put("remember_me", rep.isRememberMe());
        realmInfo.put("verify_email", rep.isVerifyEmail());
        Map<String, Object> security = new LinkedHashMap<>();
        security.put("brute_force_protection", rep.isBruteForceProtected());
        security.put("reset_password_allowed", rep.isResetPasswordAllowed());
        security.put("edit_username_allowed", rep.isEditUsernameAllowed());
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("login", rep.getLoginTheme());
        current.put("admin", rep.getAdminTheme());
        current.put("account", rep.getAccountTheme());
        current.put("email", rep.getEmailTheme());
        Map<String, Object> themes = new LinkedHashMap<>();
        themes.put("current", current);
        themes.put("available", available);
        Map<String, Object> email = new LinkedHashMap<>();
        email.put("configured", smtp.get("host") != null && !smtp.get("host").isBlank());
        email.put("host", smtp.get("host"));
        email.put("port", smtp.get("port"));
        email.put("ssl", smtp.get("ssl"));
        email.put("from_email", smtp.get("from"));
        email.put("password_configured", smtp.get("password") != null && !smtp.get("password").isEmpty());
        Map<String, Object> branding = new LinkedHashMap<>();
        branding.put("logo_configured", notBlank(attrs.get("logo_url")));
        branding.put("colors_configured", notBlank(attrs.get("primary_color")));
        branding.put("welcome_customized", notBlank(attrs.get("welcome_title")));
        Map<String, Object> localization = new LinkedHashMap<>();
        localization.put("enabled", rep.isInternationalizationEnabled());
        localization.put("supported_locales", rep.getSupportedLocales() == null ? List.of()
                : rep.getSupportedLocales().stream().sorted().toList());
        localization.put("default_locale", rep.getDefaultLocale());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("realm_info", realmInfo);
        out.put("security_settings", security);
        out.put("themes", themes);
        out.put("email_config", email);
        out.put("branding", branding);
        out.put("localization", localization);
        return out;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private RealmRepresentation realm() {
        return executors.serviceAccount().call(r -> r.toRepresentation());
    }

    private void audited(CallerPrincipal caller, String ip, String section, List<String> changed) {
        audit.emit(AuditEvent.of(AuditEventType.ADMIN_REALM_UPDATED).actor(caller.username()).requestIp(ip)
                .put("section", section).put("changed", List.copyOf(changed)).build());
    }

    private static <T> void set(List<String> changed, String name, T value, Supplier<T> get, Consumer<T> put) {
        if (value != null && !value.equals(get.get())) {
            put.accept(value);
            changed.add(name);
        }
    }

    private static void setIfGiven(List<String> changed, String name, String value, Supplier<String> get,
                                   Consumer<String> put) {
        if (value != null && !value.isBlank()) {
            set(changed, name, value, get, put);
        }
    }

    private static void putIf(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v.strip());
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
