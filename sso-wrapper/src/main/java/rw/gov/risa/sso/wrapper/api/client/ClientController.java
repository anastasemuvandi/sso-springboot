package rw.gov.risa.sso.wrapper.api.client;

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
import io.swagger.v3.oas.annotations.tags.Tag;
import rw.gov.risa.sso.wrapper.api.client.ClientRequests.ClientCreateRequest;
import rw.gov.risa.sso.wrapper.api.client.ClientRequests.ClientMfaRequest;
import rw.gov.risa.sso.wrapper.api.client.ClientRequests.ClientScopeRequest;
import rw.gov.risa.sso.wrapper.api.client.ClientRequests.ClientUpdateRequest;
import rw.gov.risa.sso.wrapper.api.client.ClientRequests.JoinColumnRequest;
import rw.gov.risa.sso.wrapper.api.client.ClientRequests.PostLogoutRedirectUrisUpdateRequest;
import rw.gov.risa.sso.wrapper.api.client.ClientRequests.RedirectUrisUpdateRequest;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;
import rw.gov.risa.sso.wrapper.service.platform.ClientAdminService;
import rw.gov.risa.sso.wrapper.service.platform.ClientAdminService.ClientView;

/**
 * Platform-admin client management (admin-tier gate in {@code RouteRules}). {@code client_id} is the
 * Keycloak clientId (its uuid is accepted too). The only surface that creates cross-cutting clients
 * or edits a client's institution scope.
 */
@RestController
@Validated
@RequestMapping("/client")
@Tag(name = "Clients")
public class ClientController {

    private final ClientAdminService clients;

    public ClientController(ClientAdminService clients) {
        this.clients = clients;
    }

    @GetMapping("/clients")
    @Operation(summary = "All clients, each with its parsed institution scope and join column")
    public List<ClientView> list() {
        return clients.list();
    }

    @PostMapping("/clients")
    @Operation(summary = "Create a client with its institution scope (owner_institution, or cross_cutting + institutions)")
    public ClientView create(@AuthenticationPrincipal CallerPrincipal caller,
                             @Valid @RequestBody ClientCreateRequest body) {
        return clients.create(new ClientAdminService.NewClient(body.clientName(), body.redirectUris(),
                body.postLogoutRedirectUris(), body.protocol(), Boolean.TRUE.equals(body.crossCutting()),
                body.institutions(), body.ownerInstitution()), caller);
    }

    @GetMapping("/clients/{client_id}")
    @Operation(summary = "One client")
    public ClientView get(@PathVariable("client_id") String clientId) {
        return clients.get(clientId);
    }

    @PutMapping("/clients/{client_id}")
    @Operation(summary = "Update a client (scope and join-column attributes are refused: use their own routes)")
    public ClientView update(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable("client_id") String clientId,
                             @Valid @RequestBody ClientUpdateRequest b) {
        return clients.update(clientId, new ClientAdminService.ClientChanges(b.clientName(), b.description(),
                b.enabled(), b.implicitFlowEnabled(), b.redirectUris(), b.publicClient(), b.protocol(),
                b.clientAuthenticatorType(), b.standardFlowEnabled(), b.directAccessGrantsEnabled(),
                b.serviceAccountsEnabled(), b.attributes()), caller);
    }

    @DeleteMapping("/clients/{client_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a client")
    public void delete(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable("client_id") String clientId) {
        clients.delete(clientId, caller);
    }

    @PutMapping("/clients/{client_id}/redirect-uris")
    @Operation(summary = "Replace the client's redirect URIs")
    public ClientView redirectUris(@AuthenticationPrincipal CallerPrincipal caller,
                                   @PathVariable("client_id") String clientId,
                                   @Valid @RequestBody RedirectUrisUpdateRequest body) {
        return clients.updateRedirectUris(clientId, body.redirectUris(), caller);
    }

    @PutMapping("/clients/{client_id}/post-logout-redirect-uris")
    @Operation(summary = "Replace the post-logout redirect URIs ('+' reuses the redirect URIs; [] clears)")
    public ClientView postLogoutRedirectUris(@AuthenticationPrincipal CallerPrincipal caller,
                                             @PathVariable("client_id") String clientId,
                                             @Valid @RequestBody PostLogoutRedirectUrisUpdateRequest body) {
        return clients.updatePostLogoutRedirectUris(clientId, body.postLogoutRedirectUris(), caller);
    }

    @GetMapping("/clients/{client_id}/secret")
    @Operation(summary = "The client's current secret (400 for a public client)")
    public Map<String, Object> secret(@PathVariable("client_id") String clientId) {
        return clients.secret(clientId);
    }

    @PostMapping("/clients/{client_id}/regenerate-secret")
    @Operation(summary = "Rotate the client secret; the old one stops working immediately")
    public Map<String, Object> regenerateSecret(@AuthenticationPrincipal CallerPrincipal caller,
                                                @PathVariable("client_id") String clientId) {
        return clients.regenerateSecret(clientId, caller);
    }

    @GetMapping("/clients/{client_id}/scope")
    @Operation(summary = "The client's institution scope, plus who each covered institution has on it")
    public ClientScopeService.ScopeState scope(@PathVariable("client_id") String clientId) {
        return clients.scopeState(clientId);
    }

    @PutMapping("/clients/{client_id}/scope")
    @Operation(summary = "Change the institution scope. 409 confirm_required (removed_institutions, access_at_risk) "
            + "before removing an institution that has users on it; with confirm=true its access is withdrawn")
    public ClientScopeService.ScopeChange setScope(@AuthenticationPrincipal CallerPrincipal caller,
                                                   @PathVariable("client_id") String clientId,
                                                   @Valid @RequestBody ClientScopeRequest body) {
        return clients.changeScope(clientId, Boolean.TRUE.equals(body.crossCutting()), body.institutions(),
                body.ownerInstitution(), Boolean.TRUE.equals(body.confirm()), caller);
    }

    @GetMapping("/clients/{client_id}/join-column")
    @Operation(summary = "The join column, the choices, live sessions; ?check=true adds who it would block")
    public Map<String, Object> joinColumn(@PathVariable("client_id") String clientId,
                                          @RequestParam(name = "check", defaultValue = "false") boolean check) {
        return clients.joinColumnState(clientId, check);
    }

    @PutMapping("/clients/{client_id}/join-column")
    @Operation(summary = "Set the join column and reconcile the sso-join-key mapper. 409 confirm_required "
            + "(active_sessions, assigned_users); 502 when the mapper write fails")
    public Map<String, Object> setJoinColumn(@AuthenticationPrincipal CallerPrincipal caller,
                                             @PathVariable("client_id") String clientId,
                                             @Valid @RequestBody JoinColumnRequest body) {
        return clients.setJoinColumn(clientId, body.joinColumn(), Boolean.TRUE.equals(body.confirm()), caller);
    }

    @GetMapping("/clients/{client_id}/mfa")
    @Operation(summary = "Whether this client requires TOTP (unset = required) and whether it applies now")
    public MfaService.ClientRequirement mfa(@PathVariable("client_id") String clientId) {
        return clients.mfa(clientId);
    }

    @PutMapping("/clients/{client_id}/mfa")
    @Operation(summary = "Require TOTP for this client, or not (super admin only, both directions)")
    public MfaService.ClientRequirementChange setMfa(@AuthenticationPrincipal CallerPrincipal caller,
                                                     @PathVariable("client_id") String clientId,
                                                     @Valid @RequestBody ClientMfaRequest body) {
        return clients.setMfa(clientId, body.required(), caller);
    }
}
