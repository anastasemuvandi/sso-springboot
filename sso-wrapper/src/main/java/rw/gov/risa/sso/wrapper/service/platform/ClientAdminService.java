package rw.gov.risa.sso.wrapper.service.platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;

import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.error.KeycloakErrors;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScopes;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumn;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumnException;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinKeys;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * {@code /client/clients/**} (port of {@code api/client.py} + {@code client_service.py}). The platform
 * surface: the only place a cross-cutting client is created or a scope edited (the admin-tier gate
 * already ran). Every Keycloak call uses the service account. Paths accept the clientId (the
 * reference's contract) and, for robustness, the internal uuid.
 */
@Service
public class ClientAdminService {

    private static final Logger log = LoggerFactory.getLogger(ClientAdminService.class);
    static final String POST_LOGOUT_ATTR = "post.logout.redirect.uris";

    private final PlatformSupport support;
    private final ClientScopeService scopes;
    private final JoinKeyService joinKeys;
    private final MfaService mfa;
    private final PlatformProperties props;

    public ClientAdminService(PlatformSupport support, JoinKeyService joinKeys, MfaService mfa,
                              PlatformProperties props) {
        this.support = support;
        this.scopes = support.scopes();
        this.joinKeys = joinKeys;
        this.mfa = mfa;
        this.props = props;
    }

    /** The reference {@code Client} schema: Keycloak's camelCase fields plus the parsed scope and join column. */
    public record ClientView(
            String id,
            String clientId,
            String name,
            String description,
            Boolean enabled,
            List<String> redirectUris,
            Boolean publicClient,
            String protocol,
            Boolean implicitFlowEnabled,
            String clientAuthenticatorType,
            Boolean standardFlowEnabled,
            Boolean directAccessGrantsEnabled,
            Boolean serviceAccountsEnabled,
            Map<String, String> attributes,
            Map<String, String> authenticationFlowBindingOverrides,
            Map<String, Object> scope,
            @JsonProperty("join_column") Map<String, Object> joinColumn) {
    }

    /** {@code POST /client/clients}. */
    public record NewClient(String clientName, List<String> redirectUris, String postLogoutRedirectUris,
                            String protocol, boolean crossCutting, List<String> institutions,
                            String ownerInstitution) {
    }

    /** {@code PUT /client/clients/{id}}: null = not sent. */
    public record ClientChanges(String clientName, String description, Boolean enabled, Boolean implicitFlowEnabled,
                                List<String> redirectUris, Boolean publicClient, String protocol,
                                String clientAuthenticatorType, Boolean standardFlowEnabled,
                                Boolean directAccessGrantsEnabled, Boolean serviceAccountsEnabled,
                                Map<String, String> attributes) {
        /** The fields sent, by their wire names (audited). */
        public List<String> sentFields() {
            List<String> out = new ArrayList<>();
            add(out, "client_name", clientName);
            add(out, "description", description);
            add(out, "enabled", enabled);
            add(out, "implicitFlowEnabled", implicitFlowEnabled);
            add(out, "redirect_uris", redirectUris);
            add(out, "public_client", publicClient);
            add(out, "protocol", protocol);
            add(out, "client_authenticator_type", clientAuthenticatorType);
            add(out, "standard_flow_enabled", standardFlowEnabled);
            add(out, "direct_access_grants_enabled", directAccessGrantsEnabled);
            add(out, "service_accounts_enabled", serviceAccountsEnabled);
            add(out, "attributes", attributes);
            return out;
        }

        private static void add(List<String> out, String name, Object v) {
            if (v != null) {
                out.add(name);
            }
        }
    }

    // ---- reads -----------------------------------------------------------------------------

    public ClientView view(ClientRepresentation c) {
        return new ClientView(c.getId(), c.getClientId(), c.getName(), c.getDescription(), c.isEnabled(),
                c.getRedirectUris(), c.isPublicClient(), c.getProtocol(), c.isImplicitFlowEnabled(),
                c.getClientAuthenticatorType(), c.isStandardFlowEnabled(), c.isDirectAccessGrantsEnabled(),
                c.isServiceAccountsEnabled(), c.getAttributes(), c.getAuthenticationFlowBindingOverrides(),
                scopes.scopeOf(c).describe(), JoinKeyService.settingOf(c).describe());
    }

    public List<ClientView> list() {
        return support.sa().call(support.directory()::listClients).stream().map(this::view).toList();
    }

    public ClientView get(String id) {
        return view(raw(id));
    }

    /** The raw representation (service account), or 404. */
    public ClientRepresentation raw(String id) {
        return support.sa().call(realm -> support.requireClient(realm, id));
    }

    /** The clientId of the client named by {@code id} (clientId or uuid), or 404. */
    public String clientIdOf(String id) {
        return raw(id).getClientId();
    }

    // ---- create ----------------------------------------------------------------------------

    /**
     * Create a client with its institution scope recorded in the same call (no window in which it has
     * no owner). 400 for an invalid scope or protocol, 409 when the clientId exists.
     */
    public ClientView create(NewClient req, CallerPrincipal caller) {
        String protocol = req.protocol() == null || req.protocol().isBlank() ? "openid-connect" : req.protocol();
        if (!"openid-connect".equals(protocol) && !"saml".equals(protocol)) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                    "Unsupported protocol '" + protocol + "'; expected 'openid-connect' or 'saml'.");
        }
        if ("saml".equals(protocol) && !props.enableSaml()) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                    "SAML support is disabled. Set ENABLE_SAML=True in the environment to enable.");
        }
        ClientScope scope = scopes.requested(req.clientName(), req.crossCutting(), req.institutions(),
                req.ownerInstitution());

        ClientRepresentation rep = new ClientRepresentation();
        rep.setClientId(req.clientName());
        rep.setName(req.clientName());
        rep.setEnabled(true);
        rep.setPublicClient(false);
        rep.setProtocol(protocol);
        rep.setClientAuthenticatorType("client-secret");
        rep.setRedirectUris(new ArrayList<>(req.redirectUris()));
        Map<String, String> attrs = new LinkedHashMap<>();
        if ("openid-connect".equals(protocol)) {
            rep.setStandardFlowEnabled(true);
            rep.setDirectAccessGrantsEnabled(true);
            rep.setServiceAccountsEnabled(true);
            rep.setImplicitFlowEnabled(false);
            rep.setAuthorizationServicesEnabled(true);
            attrs.put("pkce.code.challenge.method", "S256");
            attrs.put("oauth2.device.authorization.grant.enabled", "true");
            attrs.put("oidc.ciba.grant.enabled", "true");
            if (req.postLogoutRedirectUris() != null && !req.postLogoutRedirectUris().isBlank()) {
                attrs.put(POST_LOGOUT_ATTR, req.postLogoutRedirectUris());
            }
        } else {
            attrs.put("saml.assertion.signature", "true");
            attrs.put("saml.client.signature", "true");
            attrs.put("saml.encrypt", "false");
            attrs.put("saml.force.post.binding", "true");
            attrs.put("saml.server.signature", "true");
            attrs.put("saml.signature.algorithm", "RSA_SHA256");
            attrs.put("saml_signature_canonicalization_method", "http://www.w3.org/2001/10/xml-exc-c14n#");
            attrs.put("saml.authnstatement", "true");
        }
        // Last, so a protocol default cannot overwrite a scope attribute; blank values are dropped
        // (Keycloak keeps an empty attribute and it would read back as a deliberately empty scope).
        scope.toAttributes().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                attrs.put(k, v);
            }
        });
        rep.setAttributes(attrs);

        ClientRepresentation created = support.sa().call(realm -> {
            try (Response response = realm.clients().create(rep)) {
                int status = response.getStatus();
                if (status == 409) {
                    throw ApiException.conflict(ErrorCodes.CONFLICT,
                            "Client '" + req.clientName() + "' already exists.", Map.of());
                }
                if (status < 200 || status >= 300) {
                    String msg = PlatformSupport.errorMessage(response);
                    throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                            msg != null ? msg : "Failed to create client '" + req.clientName() + "'.");
                }
                String id = PlatformSupport.createdId(response);
                return id != null ? realm.clients().get(id).toRepresentation()
                        : support.requireClient(realm, req.clientName());
            }
        });
        log.info("platform: client '{}' created by {}", req.clientName(), caller.username());
        support.emit(support.event(AuditEventType.ADMIN_CLIENT_CREATED, caller).clientId(req.clientName())
                .putAll(support.clientTarget(created))
                .put("redirect_uris", req.redirectUris())
                .put("scope", scope.describe()));
        return view(created);
    }

    // ---- update ----------------------------------------------------------------------------

    /**
     * Generic update. Scope and join-column attributes are refused (400): they have their own routes
     * with a confirmation step, a cascade/mapper write and an audit record. Attributes are MERGED into
     * the existing map (a Keycloak PUT replaces the whole map, which would drop PKCE and scope keys).
     */
    public ClientView update(String id, ClientChanges ch, CallerPrincipal caller) {
        if (ch.attributes() != null) {
            List<String> offered = ClientScopes.scopeAttributesIn(ch.attributes().keySet());
            if (!offered.isEmpty()) {
                throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, pyList(offered)
                        + " record the client's institution scope and cannot be set through a client update. "
                        + "Use PUT /client/clients/" + id + "/scope, which warns about institutions that would "
                        + "lose access, withdraws it, and audits the change.");
            }
            offered = JoinKeys.joinColumnAttributesIn(ch.attributes().keySet());
            if (!offered.isEmpty()) {
                throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, pyList(offered)
                        + " record the client's join column and cannot be set through a client update. Use "
                        + "PUT /client/clients/" + id + "/join-column, which rewrites the protocol mapper that "
                        + "actually produces the claim, refuses to switch a client with live sessions "
                        + "unconfirmed, and audits the change.");
            }
        }
        ClientRepresentation updated = write(id, "Failed to update client '" + id + "'.", c -> {
            if (ch.clientName() != null) {
                c.setName(ch.clientName());
            }
            if (ch.description() != null) {
                c.setDescription(ch.description());
            }
            if (ch.enabled() != null) {
                c.setEnabled(ch.enabled());
            }
            if (ch.implicitFlowEnabled() != null) {
                c.setImplicitFlowEnabled(ch.implicitFlowEnabled());
            }
            if (ch.redirectUris() != null) {
                c.setRedirectUris(new ArrayList<>(ch.redirectUris()));
            }
            if (ch.publicClient() != null) {
                c.setPublicClient(ch.publicClient());
            }
            if (ch.protocol() != null) {
                c.setProtocol(ch.protocol());
            }
            if (ch.clientAuthenticatorType() != null) {
                c.setClientAuthenticatorType(ch.clientAuthenticatorType());
            }
            if (ch.standardFlowEnabled() != null) {
                c.setStandardFlowEnabled(ch.standardFlowEnabled());
            }
            if (ch.directAccessGrantsEnabled() != null) {
                c.setDirectAccessGrantsEnabled(ch.directAccessGrantsEnabled());
            }
            if (ch.serviceAccountsEnabled() != null) {
                c.setServiceAccountsEnabled(ch.serviceAccountsEnabled());
            }
            if (ch.attributes() != null) {
                Map<String, String> attrs = new HashMap<>(c.getAttributes() == null ? Map.of() : c.getAttributes());
                attrs.putAll(ch.attributes());
                c.setAttributes(attrs);
            }
        });
        support.emit(support.event(AuditEventType.ADMIN_CLIENT_UPDATED, caller).clientId(updated.getClientId())
                .putAll(support.clientTarget(updated)).put("action", "client_updated")
                .put("fields", ch.sentFields()));
        return view(updated);
    }

    public ClientView updateRedirectUris(String id, List<String> redirectUris, CallerPrincipal caller) {
        ClientRepresentation updated = write(id, "Failed to update redirect URIs for client '" + id + "'.",
                c -> c.setRedirectUris(new ArrayList<>(redirectUris)));
        support.emit(support.event(AuditEventType.ADMIN_CLIENT_UPDATED, caller).clientId(updated.getClientId())
                .putAll(support.clientTarget(updated)).put("action", "redirect_uris_updated")
                .put("redirect_uris", redirectUris));
        return view(updated);
    }

    /**
     * Stored in {@code post.logout.redirect.uris} as a {@code ##}-separated list; {@code +} reuses the
     * redirect URIs; an empty list clears it.
     */
    public ClientView updatePostLogoutRedirectUris(String id, List<String> uris, CallerPrincipal caller) {
        List<String> cleaned = uris.stream().filter(u -> u != null && !u.isBlank()).map(String::strip).toList();
        ClientRepresentation updated = write(id,
                "Failed to update post-logout redirect URIs for client '" + id + "'.", c -> {
                    Map<String, String> attrs = new HashMap<>(c.getAttributes() == null ? Map.of() : c.getAttributes());
                    attrs.put(POST_LOGOUT_ATTR, String.join("##", cleaned));
                    c.setAttributes(attrs);
                });
        support.emit(support.event(AuditEventType.ADMIN_CLIENT_UPDATED, caller).clientId(updated.getClientId())
                .putAll(support.clientTarget(updated)).put("action", "post_logout_redirect_uris_updated")
                .put("post_logout_redirect_uris", uris));
        return view(updated);
    }

    private ClientRepresentation write(String id, String failure, java.util.function.Consumer<ClientRepresentation> change) {
        return support.sa().call(realm -> {
            ClientRepresentation c = support.requireClient(realm, id);
            change.accept(c);
            try {
                realm.clients().get(c.getId()).update(c);
            } catch (WebApplicationException e) {
                int status = KeycloakErrors.status(e);
                if (status == 404) {
                    throw ApiException.notFound("Client '" + id + "' not found.");
                }
                if (status == 409) {
                    throw ApiException.conflict(ErrorCodes.CONFLICT, KeycloakErrors.message(e, failure), Map.of());
                }
                if (status == 400) {
                    throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, KeycloakErrors.message(e, failure));
                }
                throw e;
            }
            return realm.clients().get(c.getId()).toRepresentation();
        });
    }

    // ---- secret ----------------------------------------------------------------------------

    public Map<String, Object> secret(String id) {
        String value = support.sa().call(realm -> {
            ClientRepresentation c = support.requireClient(realm, id);
            if (Boolean.TRUE.equals(c.isPublicClient())) {
                throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                        "Client '" + id + "' is a public client and has no secret.");
            }
            CredentialRepresentation s = realm.clients().get(c.getId()).getSecret();
            return s == null ? null : s.getValue();
        });
        if (value == null) {
            throw ApiException.notFound("Secret not found for client '" + id + "'.");
        }
        return Map.of("client_secret", value);
    }

    /** Rotate the secret; the old one stops working at Keycloak immediately. Audited (never the value). */
    public Map<String, Object> regenerateSecret(String id, CallerPrincipal caller) {
        ClientRepresentation[] holder = new ClientRepresentation[1];
        String value = support.sa().call(realm -> {
            ClientRepresentation c = support.requireClient(realm, id);
            holder[0] = c;
            if (Boolean.TRUE.equals(c.isPublicClient())) {
                throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST,
                        "Client '" + id + "' is a public client and has no secret.");
            }
            CredentialRepresentation s = realm.clients().get(c.getId()).generateNewSecret();
            return s == null ? null : s.getValue();
        });
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.KEYCLOAK_ERROR,
                    "Failed to rotate secret for client '" + id + "'.");
        }
        support.emit(support.event(AuditEventType.ADMIN_CLIENT_SECRET_REGENERATED, caller)
                .clientId(holder[0].getClientId()).putAll(support.clientTarget(holder[0]))
                .put("action", "secret_rotated"));
        return Map.of("client_secret", value);
    }

    // ---- delete ----------------------------------------------------------------------------

    public void delete(String id, CallerPrincipal caller) {
        ClientRepresentation c = raw(id);
        // Read before the delete: afterwards there is no client to say whose it was.
        Map<String, Object> target = support.clientTarget(c);
        support.sa().run(realm -> realm.clients().get(c.getId()).remove());
        support.emit(support.event(AuditEventType.ADMIN_CLIENT_DELETED, caller).clientId(c.getClientId())
                .putAll(target));
    }

    // ---- scope -----------------------------------------------------------------------------

    public ClientScopeService.ScopeState scopeState(String id) {
        return scopes.scopeState(clientIdOf(id));
    }

    /**
     * {@code PUT …/scope}: validate (400), then the shared two-step — 409 {@code confirm_required} with
     * {@code removed_institutions}/{@code access_at_risk} before taking the client away from an
     * institution that has users on it; confirmed, it withdraws their access. Audited by the service.
     */
    public ClientScopeService.ScopeChange changeScope(String id, boolean crossCutting, List<String> institutions,
                                                      String ownerInstitution, boolean confirm, CallerPrincipal caller) {
        String clientId = clientIdOf(id);
        ClientScope after = scopes.requested(clientId, crossCutting, institutions, ownerInstitution);
        return scopes.changeScope(clientId, after, confirm, caller.username(), RequestInfo.clientIp());
    }

    // ---- join column -----------------------------------------------------------------------

    /** {@code GET …/join-column}: setting, choices, live sessions, and (with {@code check}) readiness. */
    public Map<String, Object> joinColumnState(String id, boolean check) {
        String clientId = clientIdOf(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("setting", joinKeys.getSetting(clientId).describe());
        out.put("choices", JoinKeys.choices());
        out.put("active_sessions", joinKeys.activeSessions(clientId));
        out.put("readiness", check ? joinKeys.readiness(clientId, null).describe() : null);
        return out;
    }

    /**
     * {@code PUT …/join-column}: 400 for an unknown column, 409 {@code confirm_required}
     * ({@code active_sessions}, {@code assigned_users}) unless confirmed, 502 when the mapper write fails.
     */
    public Map<String, Object> setJoinColumn(String id, String requested, boolean confirm, CallerPrincipal caller) {
        JoinColumn column;
        try {
            column = JoinKeys.requested(requested);
        } catch (JoinColumnException e) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST, e.getMessage());
        }
        ClientRepresentation c = raw(id);
        JoinKeyService.JoinColumnChange change = joinKeys.setJoinColumn(c.getClientId(), column, caller.username(),
                confirm);
        support.emit(support.event(AuditEventType.ADMIN_CLIENT_JOIN_COLUMN_CHANGED, caller).clientId(c.getClientId())
                .putAll(support.clientTarget(c))
                .put("action", "join_column_changed")
                .put("join_column_before", change.previous().column().value())
                .put("join_column_after", change.setting().column().value())
                .put("was_explicit", change.previous().explicit())
                .put("claim", change.setting().claim())
                .put("mapper", change.mapper())
                .put("active_sessions", change.activeSessions())
                .put("assigned_users", change.assignedUsers())
                .put("confirmed", confirm)
                .put("blocked_users", change.readiness().rows().size()));
        return change.describe();
    }

    // ---- MFA -------------------------------------------------------------------------------

    public MfaService.ClientRequirement mfa(String id) {
        return mfa.clientRequirement(clientIdOf(id));
    }

    /** Super admins only, both directions (the caller checks the role). Audited by {@link MfaService}. */
    public MfaService.ClientRequirementChange setMfa(String id, boolean required, CallerPrincipal caller) {
        if (!caller.isSuperAdmin()) {
            throw ApiException.forbidden(ErrorCodes.FORBIDDEN,
                    "Only a super admin may change a client's two-factor requirement.");
        }
        return mfa.setClientRequirement(clientIdOf(id), required, caller.username(), RequestInfo.clientIp(), null);
    }

    static String pyList(List<String> items) {
        return "[" + String.join(", ", items.stream().map(s -> "'" + s + "'").toList()) + "]";
    }
}
