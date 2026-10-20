package rw.gov.risa.sso.wrapper.api.authorization;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.keycloak.representations.idm.authorization.PolicyRepresentation;
import org.keycloak.representations.idm.authorization.ScopeRepresentation;
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
import rw.gov.risa.sso.wrapper.api.authorization.AuthorizationRequests.ClientAccessAssignment;
import rw.gov.risa.sso.wrapper.api.authorization.AuthorizationRequests.PolicyBody;
import rw.gov.risa.sso.wrapper.api.authorization.AuthorizationRequests.ScopeBody;
import rw.gov.risa.sso.wrapper.api.authorization.AuthorizationRequests.UserClientAccessChange;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessViews;
import rw.gov.risa.sso.wrapper.service.platform.AuthorizationAdminService;
import rw.gov.risa.sso.wrapper.service.platform.AuthorizationAdminService.PolicySpec;
import rw.gov.risa.sso.wrapper.service.platform.AuthorizationAdminService.ScopeSpec;

/**
 * Keycloak Authorization Services and per-client login access (admin-tier gate in {@code RouteRules}).
 * {@code client_uuid} routes are raw resource-server CRUD; {@code client_id} routes take the clientId.
 * The reference's {@code access_token} query parameter is not supported: the caller's bearer is the
 * credential, and platform routes act as the service account after the tier gate.
 */
@RestController
@Validated
@RequestMapping("/authorization")
@Tag(name = "Keycloak Authorization Services")
public class AuthorizationController {

    private final AuthorizationAdminService authz;

    public AuthorizationController(AuthorizationAdminService authz) {
        this.authz = authz;
    }

    @GetMapping("/client-id/{client_id}/uuid")
    @Operation(summary = "A client's uuid by clientId (404 when there is none)")
    public Map<String, String> clientUuid(@PathVariable("client_id") String clientId) {
        return authz.clientUuid(clientId);
    }

    // ---- scopes ----------------------------------------------------------------------------

    @PostMapping("/clients/{client_uuid}/scopes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an authorization scope")
    public Map<String, Object> createScope(@AuthenticationPrincipal CallerPrincipal caller,
                                           @PathVariable("client_uuid") String clientUuid,
                                           @Valid @RequestBody ScopeBody body) {
        return authz.createScope(clientUuid, new ScopeSpec(body.name(), body.displayName(), body.iconUri()), caller);
    }

    @GetMapping("/clients/{client_uuid}/scopes")
    @Operation(summary = "All authorization scopes")
    public List<ScopeRepresentation> scopes(@PathVariable("client_uuid") String clientUuid) {
        return authz.scopes(clientUuid);
    }

    @GetMapping("/clients/{client_uuid}/scopes/{scope_name}")
    @Operation(summary = "One authorization scope by name")
    public ScopeRepresentation scope(@PathVariable("client_uuid") String clientUuid,
                                     @PathVariable("scope_name") String scopeName) {
        return authz.scope(clientUuid, scopeName);
    }

    @PutMapping("/clients/{client_uuid}/scopes/{scope_name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Update an authorization scope")
    public void updateScope(@AuthenticationPrincipal CallerPrincipal caller,
                            @PathVariable("client_uuid") String clientUuid,
                            @PathVariable("scope_name") String scopeName, @Valid @RequestBody ScopeBody body) {
        authz.updateScope(clientUuid, scopeName, new ScopeSpec(body.name(), body.displayName(), body.iconUri()), caller);
    }

    @DeleteMapping("/clients/{client_uuid}/scopes/{scope_name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an authorization scope")
    public void deleteScope(@AuthenticationPrincipal CallerPrincipal caller,
                            @PathVariable("client_uuid") String clientUuid,
                            @PathVariable("scope_name") String scopeName) {
        authz.deleteScope(clientUuid, scopeName, caller);
    }

    // ---- policies --------------------------------------------------------------------------

    @PostMapping("/clients/{client_uuid}/policies")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an authorization policy (type role | group | other Keycloak types)")
    public Map<String, Object> createPolicy(@AuthenticationPrincipal CallerPrincipal caller,
                                            @PathVariable("client_uuid") String clientUuid,
                                            @Valid @RequestBody PolicyBody body) {
        return authz.createPolicy(clientUuid, spec(body), caller);
    }

    @GetMapping("/clients/{client_uuid}/policies")
    @Operation(summary = "All authorization policies (empty when authorization services are off)")
    public List<PolicyRepresentation> policies(@PathVariable("client_uuid") String clientUuid) {
        return authz.policies(clientUuid);
    }

    @PutMapping("/clients/{client_uuid}/policies")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Update an authorization policy (id required in the body)")
    public void updatePolicy(@AuthenticationPrincipal CallerPrincipal caller,
                             @PathVariable("client_uuid") String clientUuid, @Valid @RequestBody PolicyBody body) {
        authz.updatePolicy(clientUuid, spec(body), caller);
    }

    @DeleteMapping("/clients/{client_uuid}/policies/{policy_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an authorization policy")
    public void deletePolicy(@AuthenticationPrincipal CallerPrincipal caller,
                             @PathVariable("client_uuid") String clientUuid,
                             @PathVariable("policy_id") String policyId) {
        authz.deletePolicy(clientUuid, policyId, caller);
    }

    private static PolicySpec spec(PolicyBody b) {
        return new PolicySpec(b.id(), b.name(), b.description(), b.type(), b.logic(), b.decisionStrategy(), b.roles(),
                b.groups());
    }

    // ---- per-client login access -----------------------------------------------------------

    @GetMapping("/clients/{client_id}/access")
    @Operation(summary = "Users and groups assigned access to a client, plus denied_users")
    public Map<String, Object> clientAccess(@PathVariable("client_id") String clientId) {
        return authz.clientAccess(clientId);
    }

    @PutMapping("/clients/{client_id}/access")
    @Operation(summary = "Replace a client's access assignment (an institution group outside the client's "
            + "scope is refused; named users have their denial lifted)")
    public Map<String, Object> setClientAccess(@AuthenticationPrincipal CallerPrincipal caller,
                                               @PathVariable("client_id") String clientId,
                                               @Valid @RequestBody ClientAccessAssignment body) {
        return authz.setClientAccess(clientId, body.users(), body.groups(), caller);
    }

    @GetMapping("/users/{user_id}/client-access")
    @Operation(summary = "Whether this person may log in to each client (granted/open/revoked/not_granted/"
            + "exempt/unknown), by the login gate's own rule")
    public ClientAccessViews.UserAccessView userClientAccess(@PathVariable("user_id") String userId) {
        return authz.userClientAccess(userId);
    }

    @PostMapping("/users/{user_id}/client-access/{client_id}")
    @Operation(summary = "Grant, revoke or restore one client for one person. 409 state_changed / not_applicable / "
            + "denied_by_group; audited and emailed per (user, client)")
    public Map<String, Object> changeUserClientAccess(@AuthenticationPrincipal CallerPrincipal caller,
                                                      @PathVariable("user_id") String userId,
                                                      @PathVariable("client_id") String clientId,
                                                      @Valid @RequestBody UserClientAccessChange body) {
        return authz.changeUserAccess(userId, clientId, body.action(), body.expectedState(), caller);
    }
}
