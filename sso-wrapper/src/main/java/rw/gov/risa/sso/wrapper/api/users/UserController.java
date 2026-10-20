package rw.gov.risa.sso.wrapper.api.users;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import rw.gov.risa.sso.wrapper.api.users.UserRequests.BulkGroupAssignmentRequest;
import rw.gov.risa.sso.wrapper.api.users.UserRequests.CompleteUserProvisioningRequest;
import rw.gov.risa.sso.wrapper.api.users.UserRequests.CreateUserRequest;
import rw.gov.risa.sso.wrapper.api.users.UserRequests.ResetPasswordRequest;
import rw.gov.risa.sso.wrapper.api.users.UserRequests.UpdateUserRequest;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.platform.UserAdminService;
import rw.gov.risa.sso.wrapper.service.platform.UserAdminService.UserList;
import rw.gov.risa.sso.wrapper.service.platform.UserAdminService.UserView;

/**
 * Platform-admin user management (admin-tier gate in {@code RouteRules}). NID / phone / email writes
 * are validated as usable join keys: 400 {@code identifier_malformed}, 409 {@code identifier_taken}.
 * Per-application access lives under {@code /authorization/users/{user_id}/client-access}.
 */
@RestController
@Validated
@RequestMapping("/users")
@Tag(name = "User Management")
public class UserController {

    private final UserAdminService users;

    public UserController(UserAdminService users) {
        this.users = users;
    }

    @GetMapping("/")
    @Operation(summary = "Users with optional filtering (client_id, agency_id, role, search, enabled, "
            + "email_verified) and pagination")
    public UserList list(@RequestParam(name = "client_id", required = false) String clientId,
                         @RequestParam(name = "agency_id", required = false) String agencyId,
                         @RequestParam(name = "role", required = false) String role,
                         @RequestParam(name = "search", required = false) String search,
                         @RequestParam(name = "enabled", required = false) Boolean enabled,
                         @RequestParam(name = "email_verified", required = false) Boolean emailVerified,
                         @RequestParam(name = "page", defaultValue = "1") @Min(1) int page,
                         @RequestParam(name = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return users.list(new UserAdminService.UserFilter(clientId, agencyId, role, search, enabled, emailVerified),
                page, pageSize);
    }

    @GetMapping("/count")
    @Operation(summary = "Count users, optionally for one client or one agency")
    public Map<String, Object> count(@RequestParam(name = "client_id", required = false) String clientId,
                                     @RequestParam(name = "agency_id", required = false) String agencyId) {
        return users.count(clientId, agencyId);
    }

    @PostMapping("/")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a user (409 when the username/email exists)")
    public UserView create(@AuthenticationPrincipal CallerPrincipal caller, @Valid @RequestBody CreateUserRequest b) {
        return users.create(new UserAdminService.NewUser(b.username(), b.email(), b.firstName(), b.lastName(),
                !Boolean.FALSE.equals(b.enabled()), Boolean.TRUE.equals(b.emailVerified()), b.attributes()), caller);
    }

    @PostMapping("/provision")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create + password + groups in one operation (rolled back on failure)")
    public UserView provision(@AuthenticationPrincipal CallerPrincipal caller,
                              @Valid @RequestBody CompleteUserProvisioningRequest b) {
        return users.provision(new UserAdminService.NewUser(b.username(), b.email(), b.firstName(), b.lastName(),
                        !Boolean.FALSE.equals(b.enabled()), Boolean.TRUE.equals(b.emailVerified()), b.attributes()),
                b.password(), Boolean.TRUE.equals(b.temporary()), b.groupIds(), caller);
    }

    @GetMapping("/{user_id}")
    @Operation(summary = "One user with agencies, realm roles and client roles")
    public UserView get(@PathVariable("user_id") String userId) {
        return users.get(userId);
    }

    @PatchMapping("/{user_id}")
    @Operation(summary = "Partial update; attributes are merged (an empty value clears a key)")
    public UserView update(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable("user_id") String userId,
                           @Valid @RequestBody UpdateUserRequest b) {
        return users.update(userId, new UserAdminService.UserChanges(b.email(), b.firstName(), b.lastName(),
                b.enabled(), b.emailVerified(), b.attributes()), caller);
    }

    @PutMapping("/{user_id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Set or reset a user's password")
    public void resetPassword(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable("user_id") String userId,
                              @Valid @RequestBody ResetPasswordRequest b) {
        users.resetPassword(userId, b.value(), Boolean.TRUE.equals(b.temporary()), caller);
    }

    @GetMapping("/{user_id}/groups")
    @Operation(summary = "The user's groups (agencies)")
    public List<GroupRepresentation> groups(@PathVariable("user_id") String userId) {
        return users.groups(userId);
    }

    @PutMapping("/{user_id}/groups/{group_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Add the user to a group")
    public void addToGroup(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable("user_id") String userId,
                           @PathVariable("group_id") String groupId) {
        users.addToGroup(userId, groupId, caller);
    }

    @DeleteMapping("/{user_id}/groups/{group_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove the user from a group")
    public void removeFromGroup(@AuthenticationPrincipal CallerPrincipal caller,
                                @PathVariable("user_id") String userId, @PathVariable("group_id") String groupId) {
        users.removeFromGroup(userId, groupId, caller);
    }

    @PostMapping("/{user_id}/groups/bulk")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Add the user to several groups")
    public void bulkAdd(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable("user_id") String userId,
                        @Valid @RequestBody BulkGroupAssignmentRequest b) {
        users.bulk(userId, b.groupIds(), true, caller);
    }

    @DeleteMapping("/{user_id}/groups/bulk")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove the user from several groups")
    public void bulkRemove(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable("user_id") String userId,
                           @Valid @RequestBody BulkGroupAssignmentRequest b) {
        users.bulk(userId, b.groupIds(), false, caller);
    }

    @GetMapping("/{user_id}/sessions")
    @Operation(summary = "The user's active sessions")
    public List<UserSessionRepresentation> sessions(@PathVariable("user_id") String userId) {
        return users.sessions(userId);
    }

    @DeleteMapping("/{user_id}/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Log the user out of every session")
    public void logoutAll(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable("user_id") String userId) {
        users.logoutAll(userId, caller);
    }

    @DeleteMapping("/{user_id}/sessions/{session_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "End one of the user's sessions")
    public void logoutSession(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable("user_id") String userId,
                              @PathVariable("session_id") String sessionId) {
        users.logoutSession(userId, sessionId, caller);
    }
}
