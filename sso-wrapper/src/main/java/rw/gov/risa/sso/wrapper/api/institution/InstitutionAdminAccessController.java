package rw.gov.risa.sso.wrapper.api.institution;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminRequests.RevokeRequest;
import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminRequests.UsersRequest;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.institution.CurrentInstitution;
import rw.gov.risa.sso.wrapper.service.institutionadmin.AccessRevocationService;
import rw.gov.risa.sso.wrapper.service.institutionadmin.AccessRevocationService.RevokeCommand;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * Taking applications away from individual people (spec §5.13). Flow A starts at
 * {@code GET /apps/{id}/users}, flow B at {@code POST /access/clients-for-users}; both end at
 * {@code POST /access/revoke}, which holds the scope checks, the preview and the audit trail.
 */
@RestController
@Validated
@RequestMapping("/institution-admin/access")
@Tag(name = "Institution Admin")
public class InstitutionAdminAccessController {

    private final AccessRevocationService revocation;

    public InstitutionAdminAccessController(AccessRevocationService revocation) {
        this.revocation = revocation;
    }

    @PostMapping("/clients-for-users")
    @RateLimit("30/minute")
    @Operation(summary = "Flow B: which of your apps the selected users currently hold (403 if any id is not a "
            + "member of your institution)")
    public Map<String, Object> clientsForUsers(
            @CurrentInstitution(require = Capability.VIEW_CLIENTS) InstitutionContext ctx,
            @AuthenticationPrincipal CallerPrincipal caller, @Valid @RequestBody UsersRequest body) {
        return revocation.clientsForUsers(ctx, caller, body.userIds());
    }

    @PostMapping("/revoke")
    @RateLimit("15/minute")
    @Operation(summary = "Withdraw the selected users' access to the selected apps (deny-list; the person stays in "
            + "the institution group). preview/dry_run changes nothing; all_clients needs confirm_all_clients")
    public Map<String, Object> revoke(@CurrentInstitution(require = Capability.MANAGE_CLIENTS) InstitutionContext ctx,
                                      @AuthenticationPrincipal CallerPrincipal caller,
                                      @Valid @RequestBody RevokeRequest body) {
        return revocation.revoke(ctx, caller, new RevokeCommand(body.userIds(), body.clientIds(),
                Boolean.TRUE.equals(body.allClients()), Boolean.TRUE.equals(body.confirmAllClients()),
                Boolean.TRUE.equals(body.preview())), RequestInfo.clientIp());
    }
}
