package rw.gov.risa.sso.wrapper.api.institution;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminRequests.AppMfaRequest;
import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminRequests.CreateAppIn;
import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminRequests.JoinColumnRequest;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews.AssignmentView;
import rw.gov.risa.sso.wrapper.service.institution.CurrentInstitution;
import rw.gov.risa.sso.wrapper.service.institutionadmin.InstitutionAppsService;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * The institution's applications: its own {@code <slug>-*} clients plus the cross-cutting ones whose
 * scope names it. Creation takes the APP PART ONLY — the server composes {@code <slug>-<app>} from the
 * resolved institution; there is no institution argument.
 */
@RestController
@Validated
@RequestMapping("/institution-admin/apps")
@Tag(name = "Institution Admin")
public class InstitutionAdminAppsController {

    private final InstitutionAppsService apps;

    public InstitutionAdminAppsController(InstitutionAppsService apps) {
        this.apps = apps;
    }

    @GetMapping
    @RateLimit("120/minute")
    @Operation(summary = "Apps this institution may use — its own, plus cross-cutting ones (owned / manageable / "
            + "cross_cutting say which)")
    public List<Map<String, Object>> list(@CurrentInstitution(require = Capability.VIEW_CLIENTS) InstitutionContext ctx) {
        return apps.listApps(ctx);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimit("10/minute")
    @Operation(summary = "Integrate an application bound to the caller's own institution, created as "
            + "<institution>-<app>; no institution argument exists")
    public Map<String, Object> create(@CurrentInstitution(require = Capability.MANAGE_CLIENTS) InstitutionContext ctx,
                                      @Valid @RequestBody CreateAppIn body) {
        if (!body.unexpectedFields().isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "Unexpected field(s) "
                    + body.unexpectedFields().keySet() + ": send the app part only (with redirect_uris and "
                    + "post_logout_redirect_uris). The owning institution is the one you administer; it is not an "
                    + "argument.");
        }
        return apps.createApp(ctx, body.getApp(), body.getRedirectUris(), body.getPostLogoutRedirectUris(),
                RequestInfo.clientIp());
    }

    @GetMapping("/join-columns")
    @RateLimit("60/minute")
    @Operation(summary = "Backfill worklist: every app in scope and whether its join column is set")
    public Map<String, Object> joinColumns(@CurrentInstitution(require = Capability.VIEW_CLIENTS) InstitutionContext ctx) {
        return apps.joinColumnBacklog(ctx);
    }

    @GetMapping("/{clientId}/secret")
    @RateLimit("30/minute")
    @Operation(summary = "The client secret of one of this institution's OWN apps (403 for a cross-cutting app)")
    public Map<String, Object> secret(@CurrentInstitution(require = Capability.MANAGE_CLIENTS) InstitutionContext ctx,
                                      @PathVariable String clientId) {
        return apps.appSecret(ctx, clientId);
    }

    @GetMapping("/{clientId}/users")
    @RateLimit("60/minute")
    @Operation(summary = "Flow A: everyone in your institution who can currently use this app")
    public Map<String, Object> users(@CurrentInstitution(require = Capability.VIEW_CLIENTS) InstitutionContext ctx,
                                     @AuthenticationPrincipal CallerPrincipal caller, @PathVariable String clientId) {
        return apps.appUsers(ctx, caller, clientId);
    }

    @PostMapping("/{clientId}/access")
    @RateLimit("30/minute")
    @Operation(summary = "Grant this institution login access to one of its apps (additive)")
    public AssignmentView grant(@CurrentInstitution(require = Capability.MANAGE_CLIENTS) InstitutionContext ctx,
                                @AuthenticationPrincipal CallerPrincipal caller, @PathVariable String clientId) {
        return apps.grantAccess(ctx, caller, clientId, RequestInfo.clientIp());
    }

    @DeleteMapping("/{clientId}/access")
    @RateLimit("30/minute")
    @Operation(summary = "Withdraw this institution's access (its group and its named users; nobody else's)")
    public Map<String, Object> revoke(@CurrentInstitution(require = Capability.MANAGE_CLIENTS) InstitutionContext ctx,
                                      @AuthenticationPrincipal CallerPrincipal caller, @PathVariable String clientId) {
        return apps.revokeAccess(ctx, caller, clientId, RequestInfo.clientIp());
    }

    @GetMapping("/{clientId}/join-column")
    @RateLimit("60/minute")
    @Operation(summary = "Which identifier this app finds its own user record by (readable for a cross-cutting app)")
    public Map<String, Object> joinColumn(@CurrentInstitution(require = Capability.VIEW_CLIENTS) InstitutionContext ctx,
                                          @PathVariable String clientId,
                                          @Parameter(description = "Also report which assigned users the column would "
                                                  + "block") @RequestParam(defaultValue = "false") boolean check) {
        return apps.joinColumn(ctx, clientId, check);
    }

    @PutMapping("/{clientId}/join-column")
    @RateLimit("15/minute")
    @Operation(summary = "Choose the identifier one of YOUR OWN apps authenticates users by (409 confirm_required "
            + "with live sessions; 403 for a cross-cutting app)")
    public Map<String, Object> setJoinColumn(
            @CurrentInstitution(require = Capability.MANAGE_CLIENTS) InstitutionContext ctx,
            @PathVariable String clientId, @Valid @RequestBody JoinColumnRequest body) {
        return apps.setJoinColumn(ctx, clientId, body.joinColumn(), body.confirmed(), RequestInfo.clientIp());
    }

    @PutMapping("/{clientId}/mfa")
    @RateLimit("15/minute")
    @Operation(summary = "Require TOTP on one of YOUR OWN apps — on only (required:false is 403), never on a "
            + "cross-cutting app")
    public MfaService.ClientRequirementChange setMfa(
            @CurrentInstitution(require = Capability.MANAGE_CLIENTS) InstitutionContext ctx,
            @PathVariable String clientId, @Valid @RequestBody AppMfaRequest body) {
        return apps.setMfaRequired(ctx, clientId, body.required(), RequestInfo.clientIp());
    }
}
