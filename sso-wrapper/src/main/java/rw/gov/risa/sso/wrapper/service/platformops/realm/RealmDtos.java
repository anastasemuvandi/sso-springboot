package rw.gov.risa.sso.wrapper.service.platformops.realm;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request/response bodies of {@code /realm/*} (names as the reference's pydantic models). */
public final class RealmDtos {

    private RealmDtos() {
    }

    /**
     * {@code PUT /realm/config}: the editable subset of the realm. Every field is optional; only the
     * fields sent are written (the reference used {@code exclude_unset}).
     */
    @Schema(name = "RealmConfig")
    public record RealmConfig(
            String realm, String displayName, String displayNameHtml, Boolean enabled,
            String loginTheme, String adminTheme, String accountTheme, String emailTheme,
            Boolean internationalizationEnabled, List<String> supportedLocales, String defaultLocale,
            Boolean registrationAllowed, Boolean registrationEmailAsUsername, Boolean rememberMe,
            Boolean verifyEmail, Boolean loginWithEmailAllowed, Boolean duplicateEmailsAllowed,
            Boolean resetPasswordAllowed, Boolean editUsernameAllowed, Boolean bruteForceProtected,
            Boolean permanentLockout, @Min(0) Integer maxFailureWaitSeconds,
            @Min(0) Integer minimumQuickLoginWaitSeconds, @Min(0) Integer waitIncrementSeconds,
            @Min(0) Long quickLoginCheckMilliSeconds, @Min(0) Integer maxDeltaTimeSeconds,
            @Min(1) Integer failureFactor) {
    }

    /** {@code PUT /realm/branding}: blank/absent fields are left as they are. */
    @Schema(name = "BrandingConfig")
    public record BrandingConfig(
            @JsonProperty("logo_url") String logoUrl,
            @JsonProperty("primary_color") String primaryColor,
            @JsonProperty("secondary_color") String secondaryColor,
            @JsonProperty("background_color") String backgroundColor,
            @JsonProperty("login_theme") String loginTheme,
            @JsonProperty("admin_theme") String adminTheme,
            @JsonProperty("account_theme") String accountTheme,
            @JsonProperty("email_theme") String emailTheme,
            @JsonProperty("favicon_url") String faviconUrl,
            @JsonProperty("welcome_title") String welcomeTitle,
            @JsonProperty("welcome_message") String welcomeMessage) {
    }

    /**
     * {@code PUT /realm/email/config}: Keycloak's {@code smtpServer}. A null/blank {@code password} keeps
     * the stored one (Keycloak never returns it, so the console cannot send it back).
     */
    @Schema(name = "SMTPConfig")
    public record SmtpConfig(
            @NotBlank String host,
            @Min(1) @Max(65535) Integer port,
            @NotBlank @Email String from,
            String fromDisplayName,
            @Email String replyTo,
            String replyToDisplayName,
            @Email String envelopeFrom,
            Boolean ssl,
            Boolean starttls,
            Boolean auth,
            String user,
            @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String password) {
    }

    @Schema(name = "SMTPTestResult")
    public record SmtpTestResult(boolean success, String message) {
    }

    @Schema(name = "ThemesResponse")
    public record ThemesResponse(List<String> login, List<String> admin, List<String> account, List<String> email) {
    }

    public record Message(String message) {
    }

    public record LogoUploaded(String message, @JsonProperty("logo_url") String logoUrl, String filename,
                               long size) {
    }
}
