package rw.gov.risa.sso.wrapper.api.admin;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.platformops.admin.AdminViewsService;
import rw.gov.risa.sso.wrapper.service.platformops.admin.AdminViewsService.AgencyDashboard;
import rw.gov.risa.sso.wrapper.service.platformops.admin.AdminViewsService.AssignRoles;
import rw.gov.risa.sso.wrapper.service.platformops.admin.AdminViewsService.ClientDashboard;
import rw.gov.risa.sso.wrapper.service.platformops.admin.AdminViewsService.ClientUser;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * {@code /admin/*} (admin tier): per-agency, per-client and system views with real Keycloak data (the
 * reference served mock data here).
 */
@RestController
@RequestMapping("/admin")
@Tag(name = "Admin")
public class AdminViewsController {

    private final AdminViewsService views;

    public AdminViewsController(AdminViewsService views) {
        this.views = views;
    }

    @GetMapping("/agencies/{agency_code}/dashboard")
    @RateLimit("60/minute")
    @Operation(summary = "An institution's dashboard: members (whole group tree), departments, applications")
    public AgencyDashboard agencyDashboard(@PathVariable("agency_code") String agencyCode) {
        return views.agencyDashboard(agencyCode);
    }

    @GetMapping("/agencies/{agency_code}/groups")
    @RateLimit("60/minute")
    @Operation(summary = "An institution's departments (direct child groups) with member counts")
    public Map<String, Object> agencyGroups(@PathVariable("agency_code") String agencyCode) {
        return views.agencyGroups(agencyCode);
    }

    @PostMapping("/agencies/{agency_code}/groups")
    @RateLimit("20/minute")
    @Operation(summary = "Create a department under an institution group ({\"name\", \"description\"?})")
    public Map<String, Object> createAgencyGroup(@PathVariable("agency_code") String agencyCode,
                                                 @RequestBody Map<String, Object> groupData,
                                                 @AuthenticationPrincipal CallerPrincipal caller) {
        return views.createAgencyGroup(agencyCode, groupData, caller, RequestInfo.clientIp());
    }

    @GetMapping("/clients/{client_id}/dashboard")
    @RateLimit("60/minute")
    @Operation(summary = "A client's dashboard: people with access, live sessions, last login, its roles")
    public ClientDashboard clientDashboard(@PathVariable("client_id") String clientId) {
        return views.clientDashboard(clientId);
    }

    @GetMapping("/clients/{client_id}/users")
    @RateLimit("60/minute")
    @Operation(summary = "People holding the client's roles or named on its access policy")
    public List<ClientUser> clientUsers(@PathVariable("client_id") String clientId) {
        return views.clientUsers(clientId);
    }

    @PostMapping("/clients/{client_id}/users/{user_id}/roles")
    @RateLimit("30/minute")
    @Operation(summary = "Assign client roles to a user")
    public Map<String, Object> assignClientRoles(@PathVariable("client_id") String clientId,
                                                 @PathVariable("user_id") String userId,
                                                 @RequestBody AssignRoles request,
                                                 @AuthenticationPrincipal CallerPrincipal caller) {
        return views.assignClientRoles(clientId, userId, request.roles(), caller, RequestInfo.clientIp());
    }

    @GetMapping("/system/clients")
    @RateLimit("60/minute")
    @Operation(summary = "Every client in the realm with its scope")
    public Map<String, Object> systemClients() {
        return views.systemClients();
    }

    @GetMapping("/system/users")
    @RateLimit("60/minute")
    @Operation(summary = "All users, paginated (super admin)")
    public Map<String, Object> systemUsers(@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
                                           @RequestParam(defaultValue = "0") @Min(0) int offset,
                                           @AuthenticationPrincipal CallerPrincipal caller) {
        if (caller == null || !caller.isSuperAdmin()) {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN, "Only a super admin may list every user.");
        }
        return views.systemUsers(limit, offset);
    }
}
