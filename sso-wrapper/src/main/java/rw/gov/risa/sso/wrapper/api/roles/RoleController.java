package rw.gov.risa.sso.wrapper.api.roles;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import rw.gov.risa.sso.wrapper.api.roles.RoleRequests.RoleRequest;
import rw.gov.risa.sso.wrapper.api.roles.RoleRequests.UserRoleAssignmentRequest;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.platform.RoleAdminService;
import rw.gov.risa.sso.wrapper.service.platform.RoleAdminService.RoleClientInfo;
import rw.gov.risa.sso.wrapper.service.platform.RoleAdminService.RoleSpec;
import rw.gov.risa.sso.wrapper.service.platform.RoleAdminService.RoleStatistics;
import rw.gov.risa.sso.wrapper.service.platform.RoleAdminService.RoleView;
import rw.gov.risa.sso.wrapper.service.platform.RoleAdminService.UserRoleInfo;

/**
 * Realm roles, client roles and user role mappings (admin-tier gate in {@code RouteRules}; the
 * reference's admin-only check refused super_admin, which spec §5.16 forbids). {@code client_id} in a
 * path is the client's uuid as in the reference (its clientId is accepted too).
 */
@RestController
@Validated
@RequestMapping("/roles")
@Tag(name = "Roles")
public class RoleController {

    private final RoleAdminService roles;

    public RoleController(RoleAdminService roles) {
        this.roles = roles;
    }

    private static RoleSpec spec(RoleRequest r) {
        return new RoleSpec(r.name(), r.description(), Boolean.TRUE.equals(r.composite()), r.attributes());
    }

    private static List<String> names(UserRoleAssignmentRequest r) {
        List<String> names = r.roleNames();
        if (names.isEmpty()) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, "Every role must carry a 'name'.");
        }
        return names;
    }

    // ---- realm roles -----------------------------------------------------------------------

    @GetMapping("/realm-roles")
    @Operation(summary = "All realm roles")
    public List<RoleView> realmRoles() {
        return roles.realmRoles();
    }

    @PostMapping("/realm-roles")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a realm role")
    public Map<String, String> createRealmRole(@AuthenticationPrincipal CallerPrincipal caller,
                                               @Valid @RequestBody RoleRequest body) {
        return roles.createRealmRole(spec(body), caller);
    }

    @PutMapping("/realm-roles/{role_name}")
    @Operation(summary = "Update a realm role")
    public Map<String, String> updateRealmRole(@AuthenticationPrincipal CallerPrincipal caller,
                                               @PathVariable("role_name") String roleName,
                                               @Valid @RequestBody RoleRequest body) {
        return roles.updateRealmRole(roleName, spec(body), caller);
    }

    @DeleteMapping("/realm-roles/{role_name}")
    @Operation(summary = "Delete a realm role")
    public Map<String, String> deleteRealmRole(@AuthenticationPrincipal CallerPrincipal caller,
                                               @PathVariable("role_name") String roleName) {
        return roles.deleteRealmRole(roleName, caller);
    }

    // ---- client roles ----------------------------------------------------------------------

    @GetMapping("/clients/{client_id}/roles")
    @Operation(summary = "A client's roles")
    public List<RoleView> clientRoles(@PathVariable("client_id") String clientId) {
        return roles.clientRoles(clientId);
    }

    @PostMapping("/clients/{client_id}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a client role")
    public Map<String, String> createClientRole(@AuthenticationPrincipal CallerPrincipal caller,
                                                @PathVariable("client_id") String clientId,
                                                @Valid @RequestBody RoleRequest body) {
        return roles.createClientRole(clientId, spec(body), caller);
    }

    @PutMapping("/clients/{client_id}/roles/{role_name}")
    @Operation(summary = "Update a client role")
    public Map<String, String> updateClientRole(@AuthenticationPrincipal CallerPrincipal caller,
                                                @PathVariable("client_id") String clientId,
                                                @PathVariable("role_name") String roleName,
                                                @Valid @RequestBody RoleRequest body) {
        return roles.updateClientRole(clientId, roleName, spec(body), caller);
    }

    @DeleteMapping("/clients/{client_id}/roles/{role_name}")
    @Operation(summary = "Delete a client role")
    public Map<String, String> deleteClientRole(@AuthenticationPrincipal CallerPrincipal caller,
                                                @PathVariable("client_id") String clientId,
                                                @PathVariable("role_name") String roleName) {
        return roles.deleteClientRole(clientId, roleName, caller);
    }

    // ---- user role mappings ----------------------------------------------------------------

    @GetMapping("/users/{user_id}/roles")
    @Operation(summary = "A user's directly assigned realm and client roles")
    public UserRoleInfo userRoles(@PathVariable("user_id") String userId) {
        return roles.userRoles(userId);
    }

    @PostMapping("/users/{user_id}/realm-roles")
    @Operation(summary = "Assign realm roles to a user (mfa-exempt is refused: use /realm/mfa/exemptions)")
    public Map<String, String> assignRealmRoles(@AuthenticationPrincipal CallerPrincipal caller,
                                                @PathVariable("user_id") String userId,
                                                @Valid @RequestBody UserRoleAssignmentRequest body) {
        return roles.changeUserRealmRoles(userId, names(body), true, caller);
    }

    @DeleteMapping("/users/{user_id}/realm-roles")
    @Operation(summary = "Remove realm roles from a user")
    public Map<String, String> removeRealmRoles(@AuthenticationPrincipal CallerPrincipal caller,
                                                @PathVariable("user_id") String userId,
                                                @Valid @RequestBody UserRoleAssignmentRequest body) {
        return roles.changeUserRealmRoles(userId, names(body), false, caller);
    }

    @PostMapping("/users/{user_id}/client-roles/{client_id}")
    @Operation(summary = "Assign client roles to a user")
    public Map<String, String> assignClientRoles(@AuthenticationPrincipal CallerPrincipal caller,
                                                 @PathVariable("user_id") String userId,
                                                 @PathVariable("client_id") String clientId,
                                                 @Valid @RequestBody UserRoleAssignmentRequest body) {
        return roles.changeUserClientRoles(userId, clientId, names(body), true, caller);
    }

    @DeleteMapping("/users/{user_id}/client-roles/{client_id}")
    @Operation(summary = "Remove client roles from a user")
    public Map<String, String> removeClientRoles(@AuthenticationPrincipal CallerPrincipal caller,
                                                 @PathVariable("user_id") String userId,
                                                 @PathVariable("client_id") String clientId,
                                                 @Valid @RequestBody UserRoleAssignmentRequest body) {
        return roles.changeUserClientRoles(userId, clientId, names(body), false, caller);
    }

    // ---- catalogue -------------------------------------------------------------------------

    @GetMapping("/statistics")
    @Operation(summary = "Role counts; composite realm roles are reported as system roles")
    public RoleStatistics statistics() {
        return roles.statistics();
    }

    @GetMapping("/clients")
    @Operation(summary = "Clients for role management")
    public List<RoleClientInfo> clients() {
        return roles.clients();
    }

    @GetMapping("/client-id/{client_id}/uuid")
    @Operation(summary = "A client's uuid by clientId (null when there is none)")
    public Map<String, String> clientUuid(@PathVariable("client_id") String clientId) {
        return roles.clientUuid(clientId);
    }
}
