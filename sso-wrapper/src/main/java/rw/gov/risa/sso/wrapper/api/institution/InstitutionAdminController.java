package rw.gov.risa.sso.wrapper.api.institution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminRequests.CreateUserIn;
import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminRequests.RoleIn;
import rw.gov.risa.sso.wrapper.api.institution.InstitutionAdminRequests.UpdateUserIn;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.institution.CurrentInstitution;
import rw.gov.risa.sso.wrapper.service.institutionadmin.InstitutionAdminOverviewService;
import rw.gov.risa.sso.wrapper.service.institutionadmin.InstitutionAuditService;
import rw.gov.risa.sso.wrapper.service.institutionadmin.InstitutionUsersService;
import rw.gov.risa.sso.wrapper.service.institutionadmin.InstitutionUsersService.UserInput;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * The delegated (local) admin's own door: context, overview, audit trail, users and roles. Every
 * route resolves the caller to an {@link InstitutionContext} ({@link CurrentInstitution}; 503 while the
 * templates are unusable, 403 for an institution not administered) and declares the capability it
 * consumes; the service re-asserts it and applies the mandatory institution filter.
 */
@RestController
@Validated
@RequestMapping("/institution-admin")
@Tag(name = "Institution Admin")
public class InstitutionAdminController {

    private final InstitutionUsersService users;
    private final InstitutionAdminOverviewService overview;
    private final InstitutionAuditService audit;

    public InstitutionAdminController(InstitutionUsersService users, InstitutionAdminOverviewService overview,
                                      InstitutionAuditService audit) {
        this.users = users;
        this.overview = overview;
        this.audit = audit;
    }

    // ---- context ---------------------------------------------------------------------------

    @GetMapping("/me")
    @Operation(summary = "The caller's resolved institution context (drives the console's mode)")
    public Map<String, Object> me(@CurrentInstitution InstitutionContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("institution", ctx.institution());
        out.put("administered", ctx.administered());
        out.put("is_platform_admin", ctx.platformAdmin());
        out.put("actor", ctx.actor());
        out.put("capabilities", ctx.capabilities());
        return out;
    }

    @GetMapping("/permissions")
    @Operation(summary = "The caller's effective delegated permissions, resolved from the templates")
    public Map<String, Object> permissions(@CurrentInstitution InstitutionContext ctx) {
        return ctx.grant().describe();
    }

    @GetMapping("/overview")
    @RateLimit("30/minute")
    @Operation(summary = "What needs attention in this institution: leavers, password setup, logins, access per "
            + "application — each section with available/error")
    public Map<String, Object> overview(@CurrentInstitution(require = Capability.VIEW_MEMBERS) InstitutionContext ctx,
                                        @AuthenticationPrincipal CallerPrincipal caller) {
        return overview.overview(ctx, caller);
    }

    @GetMapping("/audit")
    @RateLimit("60/minute")
    @Operation(summary = "This institution's audit trail; the institution filter comes from the resolved context, "
            + "never from a parameter")
    public Map<String, Object> audit(@CurrentInstitution(require = Capability.VIEW_MEMBERS) InstitutionContext ctx,
                                     @RequestParam(name = "event_type", required = false) String eventType,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) String target,
                                     @RequestParam(required = false) String severity,
                                     @RequestParam(name = "from_date", required = false) String fromDate,
                                     @RequestParam(name = "to_date", required = false) String toDate,
                                     @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit,
                                     @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return audit.events(ctx, new InstitutionAuditService.Filters(eventType, category, target, severity, fromDate,
                toDate, limit, offset));
    }

    // ---- users -----------------------------------------------------------------------------

    @GetMapping("/users")
    @RateLimit("120/minute")
    @Operation(summary = "List/search users — ONLY the caller's institution")
    public List<Map<String, Object>> listUsers(
            @CurrentInstitution(require = Capability.VIEW_MEMBERS) InstitutionContext ctx,
            @AuthenticationPrincipal CallerPrincipal caller,
            @Parameter(description = "Filter within your institution only") @RequestParam(required = false) String search) {
        return users.listUsers(ctx, caller, search);
    }

    @GetMapping("/users/{userId}")
    @RateLimit("120/minute")
    @Operation(summary = "One member of the caller's institution (403 for anyone else)")
    public Map<String, Object> getUser(@CurrentInstitution(require = Capability.VIEW_MEMBERS) InstitutionContext ctx,
                                       @AuthenticationPrincipal CallerPrincipal caller,
                                       @PathVariable String userId) {
        return users.getUser(ctx, caller, userId);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimit("30/minute")
    @Operation(summary = "Create a user pinned to the caller's institution (attribute + group)")
    public Map<String, Object> createUser(
            @CurrentInstitution(require = Capability.MANAGE_MEMBERS) InstitutionContext ctx,
            @AuthenticationPrincipal CallerPrincipal caller, @Valid @RequestBody CreateUserIn body) {
        return users.createUser(ctx, caller, new UserInput(body.username(), body.email(), body.firstName(),
                body.lastName(), body.enabled(), body.emailVerified(), body.nationalId(), body.phone()),
                RequestInfo.clientIp());
    }

    @PatchMapping("/users/{userId}")
    @RateLimit("30/minute")
    @Operation(summary = "Update one member; the institution attribute cannot be changed")
    public Map<String, Object> updateUser(
            @CurrentInstitution(require = Capability.MANAGE_MEMBERS) InstitutionContext ctx,
            @AuthenticationPrincipal CallerPrincipal caller, @PathVariable String userId,
            @Valid @RequestBody UpdateUserIn body) {
        return users.updateUser(ctx, caller, userId, new UserInput(null, body.email(), body.firstName(),
                body.lastName(), body.enabled(), body.emailVerified(), body.nationalId(), body.phone()),
                RequestInfo.clientIp());
    }

    @PostMapping("/users/{userId}/deactivate")
    @RateLimit("30/minute")
    @Operation(summary = "Disable the account and terminate its sessions")
    public Map<String, Object> deactivateUser(
            @CurrentInstitution(require = Capability.MANAGE_MEMBERS) InstitutionContext ctx,
            @AuthenticationPrincipal CallerPrincipal caller, @PathVariable String userId) {
        return users.deactivateUser(ctx, caller, userId, RequestInfo.clientIp());
    }

    // ---- roles (inst:<slug>:* only) --------------------------------------------------------

    @GetMapping("/roles")
    @RateLimit("120/minute")
    @Operation(summary = "Roles this admin may assign — the institution's own namespace only")
    public List<Map<String, Object>> roles(@CurrentInstitution(require = Capability.MAP_ROLES) InstitutionContext ctx) {
        return users.assignableRoles(ctx);
    }

    @PostMapping("/users/{userId}/roles")
    @RateLimit("60/minute")
    @Operation(summary = "Assign one of the institution's own roles to one of its members")
    public Map<String, Object> assignRole(@CurrentInstitution(require = Capability.MAP_ROLES) InstitutionContext ctx,
                                          @AuthenticationPrincipal CallerPrincipal caller, @PathVariable String userId,
                                          @Valid @RequestBody RoleIn body) {
        return users.assignRole(ctx, caller, userId, body.role(), RequestInfo.clientIp());
    }

    @DeleteMapping("/users/{userId}/roles")
    @RateLimit("60/minute")
    @Operation(summary = "Remove one of the institution's own roles from one of its members")
    public Map<String, Object> revokeRole(@CurrentInstitution(require = Capability.MAP_ROLES) InstitutionContext ctx,
                                          @AuthenticationPrincipal CallerPrincipal caller, @PathVariable String userId,
                                          @Valid @RequestBody RoleIn body) {
        return users.revokeRole(ctx, caller, userId, body.role(), RequestInfo.clientIp());
    }
}
