package rw.gov.risa.sso.wrapper.api.realm;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;
import rw.gov.risa.sso.wrapper.service.platformops.realm.RealmDtos.BrandingConfig;
import rw.gov.risa.sso.wrapper.service.platformops.realm.RealmDtos.LogoUploaded;
import rw.gov.risa.sso.wrapper.service.platformops.realm.RealmDtos.RealmConfig;
import rw.gov.risa.sso.wrapper.service.platformops.realm.RealmDtos.SmtpConfig;
import rw.gov.risa.sso.wrapper.service.platformops.realm.RealmDtos.SmtpTestResult;
import rw.gov.risa.sso.wrapper.service.platformops.realm.RealmDtos.ThemesResponse;
import rw.gov.risa.sso.wrapper.service.platformops.realm.RealmSettingsService;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * {@code /realm/*} (admin tier). Reads are open to every platform admin; every write — config, branding,
 * logo, SMTP, the SMTP test, the TOTP mode and exemptions — is {@code super_admin} only, as in the
 * reference.
 */
@RestController
@RequestMapping("/realm")
@Tag(name = "Realm")
public class RealmController {

    private final RealmSettingsService realm;
    private final MfaService mfa;

    public RealmController(RealmSettingsService realm, MfaService mfa) {
        this.realm = realm;
        this.mfa = mfa;
    }

    // ---- config ----------------------------------------------------------------------------

    @GetMapping("/config")
    @RateLimit("60/minute")
    @Operation(summary = "Realm configuration (the SMTP password is never included)")
    public Map<String, Object> config() {
        return realm.config();
    }

    @PutMapping("/config")
    @RateLimit("20/minute")
    @Operation(summary = "Update the realm configuration (super admin; only the fields sent are written)")
    public Map<String, Object> updateConfig(@Valid @RequestBody RealmConfig body,
                                            @AuthenticationPrincipal CallerPrincipal caller) {
        requireSuperAdmin(caller);
        return realm.updateConfig(body, caller, RequestInfo.clientIp());
    }

    // ---- email -----------------------------------------------------------------------------

    @GetMapping("/email/config")
    @RateLimit("60/minute")
    @Operation(summary = "SMTP configuration (Keycloak's smtpServer, without the password)")
    public Map<String, Object> emailConfig() {
        return realm.emailConfig();
    }

    @PutMapping("/email/config")
    @RateLimit("20/minute")
    @Operation(summary = "Update the SMTP configuration (super admin; no password = keep the stored one)")
    public Map<String, Object> updateEmailConfig(@Valid @RequestBody SmtpConfig body,
                                                 @AuthenticationPrincipal CallerPrincipal caller) {
        requireSuperAdmin(caller);
        return realm.updateEmailConfig(body, caller, RequestInfo.clientIp());
    }

    @PostMapping("/email/test")
    @RateLimit("5/minute")
    @Operation(summary = "Test the stored SMTP settings through Keycloak (super admin)")
    public SmtpTestResult testEmail(@AuthenticationPrincipal CallerPrincipal caller) {
        requireSuperAdmin(caller);
        return realm.testEmail(caller);
    }

    // ---- themes and branding ---------------------------------------------------------------

    @GetMapping("/themes")
    @RateLimit("60/minute")
    @Operation(summary = "Available themes per type")
    public ThemesResponse themes() {
        return realm.themes();
    }

    @GetMapping("/branding")
    @RateLimit("60/minute")
    @Operation(summary = "Branding: themes, display name and branding attributes")
    public Map<String, Object> branding() {
        return realm.branding();
    }

    @PutMapping("/branding")
    @RateLimit("20/minute")
    @Operation(summary = "Update branding (super admin; blank fields are left as they are)")
    public Map<String, Object> updateBranding(@Valid @RequestBody BrandingConfig body,
                                              @AuthenticationPrincipal CallerPrincipal caller) {
        requireSuperAdmin(caller);
        return realm.updateBranding(body, caller, RequestInfo.clientIp());
    }

    @PostMapping(value = "/branding/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit("10/minute")
    @Operation(summary = "Upload the logo (super admin; JPEG/PNG/GIF/SVG, at most 5 MB, content checked)")
    public LogoUploaded uploadLogo(@RequestParam("file") MultipartFile file,
                                   @AuthenticationPrincipal CallerPrincipal caller) throws IOException {
        requireSuperAdmin(caller);
        return realm.uploadLogo(file.getOriginalFilename(), file.getContentType(), file.getBytes(), caller,
                RequestInfo.clientIp());
    }

    @GetMapping("/overview")
    @RateLimit("60/minute")
    @Operation(summary = "Realm settings overview")
    public Map<String, Object> overview() {
        return realm.overview();
    }

    // ---- TOTP (MfaService owns the rule, the writes and their audit) ------------------------

    @Schema(name = "MfaSwitchRequest")
    public record MfaSwitchRequest(
            @Schema(description = "off | per_client | all. per_client: a client requires TOTP unless set "
                    + "otherwise on the client.") String mode,
            @Schema(description = "The original switch, kept for existing callers: true = all, false = off.")
            Boolean enforced) {
    }

    @Schema(name = "MfaExemptionRequest")
    public record MfaExemptionRequest(
            @Schema(description = "Keycloak user id") @JsonProperty("user_id") @NotBlank String userId,
            @Schema(description = "Justification (15–500 characters), recorded in the user attribute "
                    + "mfa_exempt_reason") @NotBlank String reason) {
    }

    public record ExemptionList(List<MfaService.Exemption> exemptions) {
    }

    @GetMapping("/mfa")
    @RateLimit("60/minute")
    @Operation(summary = "Current state of the realm-wide TOTP switch")
    public Map<String, Object> mfaState() {
        return mfa.getState();
    }

    @PutMapping("/mfa")
    @RateLimit("10/minute")
    @Operation(summary = "Set the TOTP mode off | per_client | all (super admin; audited with old/new value)")
    public MfaService.ModeChange setMfa(@RequestBody MfaSwitchRequest body,
                                        @AuthenticationPrincipal CallerPrincipal caller) {
        requireSuperAdmin(caller);
        if (body.mode() != null) {
            return mfa.setMode(body.mode(), caller.username(), RequestInfo.clientIp());
        }
        if (body.enforced() != null) {
            return mfa.setEnforced(body.enforced(), caller.username(), RequestInfo.clientIp());
        }
        throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "Send `mode` (off | per_client | all).");
    }

    @GetMapping("/mfa/exemptions")
    @RateLimit("60/minute")
    @Operation(summary = "Users holding mfa-exempt, with the recorded justification")
    public ExemptionList exemptions() {
        return new ExemptionList(mfa.listExemptions());
    }

    @PostMapping("/mfa/exemptions")
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimit("20/minute")
    @Operation(summary = "Exempt one user from TOTP (super admin; a justification is required)")
    public MfaService.Exemption grantExemption(@Valid @RequestBody MfaExemptionRequest body,
                                               @AuthenticationPrincipal CallerPrincipal caller) {
        requireSuperAdmin(caller);
        return mfa.grantExemption(body.userId(), body.reason(), caller.username(), RequestInfo.clientIp());
    }

    @DeleteMapping("/mfa/exemptions/{user_id}")
    @RateLimit("20/minute")
    @Operation(summary = "Remove a user's exemption; their next login asks for TOTP again (super admin)")
    public Map<String, Object> revokeExemption(@PathVariable("user_id") String userId,
                                               @AuthenticationPrincipal CallerPrincipal caller) {
        requireSuperAdmin(caller);
        return mfa.revokeExemption(userId, caller.username(), RequestInfo.clientIp());
    }

    static void requireSuperAdmin(CallerPrincipal caller) {
        if (caller == null || !caller.isSuperAdmin()) {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN, "Only a super admin may change this.");
        }
    }
}
